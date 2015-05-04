(ns datasplash.api-test
  (:require [clojure.test :refer :all]
            [datasplash.api :as ds]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [com.google.cloud.dataflow.sdk.testing TestPipeline DataflowAssert]
           [java.io PushbackReader]))

(defn glob-file
  [path]
  (fs/glob (str path "*")))

(defmacro with-files
  [files & body]
  `(let [~@(flatten
            (for [file files]
              [file (fs/temp-name (name file) ".edn")]))]
     (try
       ~@body
       (finally
         ~@(for [file files]
             `(doseq [f# (glob-file ~file)]
                (fs/delete (.getPath f#))))))))

(defn read-file
  [path]
  (with-open [rdr (PushbackReader. (io/reader path))]
    (loop [acc []]
      (let [line (edn/read {:eof ::eof} rdr)]
        (if (= line ::eof)
          acc
          (recur (conj acc line)))))))

(defn make-test-pipeline
  []
  (let [p (TestPipeline/create)
        coder-registry (.getCoderRegistry p)]
    (doto coder-registry
      (.registerCoder clojure.lang.IPersistentCollection (ds/make-transit-coder))
      (.registerCoder clojure.lang.Keyword (ds/make-transit-coder)))
    p))

(deftest basic-pipeline
  (let [p (make-test-pipeline)
        input (ds/generate-input [1 2 3 4 5] p)
        proc (ds/map inc {:name "increment"} input)
        filter (ds/filter even? proc)]
    (.. DataflowAssert (that input) (containsInAnyOrder #{1 2 3 4 5}))
    (.. DataflowAssert (that proc) (containsInAnyOrder #{2 3 4 5 6}))
    (.. DataflowAssert (that filter) (containsInAnyOrder #{2 4 6}))
    (is "increment" (.getName proc))
    (.run p)))


;; (deftest side-inputs-test
;;   (with-files [side-test]
;;     (let [p (make-test-pipeline)
;;           input (ds/generate-input [1 2 3 4 5] p)
;;           side-input (ds/view (ds/generate-input {1 :toto 2 :tata 3 :hello 4 :bye } p))
;;           proc (ds/map-side (fn [x side] (concat (get side x) x)) {:name "concat"} input side-input)
;;           output (ds/write-edn-file side-test proc)]
;;       (.run p))
;;     (let [res (read-file (first (glob-file side-test)))]
;;       (println res))))

(deftest group-test
  (with-files [group-test]
    (let [p (make-test-pipeline)
          input (ds/generate-input [{:key :a :val 42} {:key :b :val 56} {:key :a :lue 65}] p)
          grouped (ds/group-by :key {:name "group"} input)
          output (ds/write-edn-file group-test grouped)]
      (is "group" (.getName grouped))
      (.run p)
      (let [res (into #{} (read-file (first (glob-file group-test))))]
        (is (res [:a [{:key :a :lue 65} {:key :a :val 42}]]))
        (is (res [:b [{:key :b :val 56}]]))))))

(deftest cogroup-test
  (with-files [cogroup-test]
    (let [p (make-test-pipeline)
          input1 (ds/generate-input [{:key :a :val 42} {:key :b :val 56} {:key :a :lue 65}] p)
          input2 (ds/generate-input [{:key :a :lav 42} {:key :a :uel 65} {:key :c :foo 42}] p)
          grouped (ds/cogroup-by {:name "cogroup-test"}
                                 [[input1 :key] [input2 :key]])
          output (ds/write-edn-file cogroup-test grouped)]
      (.run p)
      (is "cogroup-test" (.getName grouped))
      (let [res (into #{} (read-file (first (glob-file cogroup-test))))]
        (is (= res #{[:a [{:key :a, :lue 65} {:key :a, :val 42}] [{:key :a, :uel 65} {:key :a, :lav 42}]]
                     [:c [] [{:key :c, :foo 42}]] [:b [{:key :b, :val 56}] []]}))))))

(deftest combine-pipeline
  (let [p (make-test-pipeline)
        input (ds/generate-input [1 2 3 4 5] p)
        proc (ds/combine-globally + {:name "combine"} input)]
    (.. DataflowAssert (that proc) (containsInAnyOrder #{15}))
    (is "combine" (.getName proc))
    (.run p)))