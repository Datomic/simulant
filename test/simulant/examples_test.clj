(ns simulant.examples-test
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(defn read-all
  "Read all forms in f, where f is any resource that can
   be opened by io/reader"
  [f]
  (datomic.Util/readAll (io/reader f)))

(defn examples-seq
  []
  (->> (file-seq (io/file "examples/repl"))
       (filter #(.endsWith (.getName ^java.io.File %) ".clj"))))

(defn transcript
  "Run all forms, printing a transcript as if forms were
   individually entered interactively at the REPL."
  [forms]
  (binding [*ns* *ns*]
    (let [temp (gensym)]
      (println ";; Executing forms in temp namespace: " temp)
      (in-ns temp)
      (clojure.core/use 'clojure.core 'clojure.repl 'clojure.pprint)
      (loop [forms forms]
        (when-let [f (first forms)]
          (if (and (seq? f) (= 'comment (first f)))
            (recur (concat (rest f) (rest forms)))
            (do
              (pprint/pprint f)
              (print "=> ")
              (pprint/pprint (eval f))
              (println)
              (recur (rest forms))))))
      (remove-ns temp)
      :done)))

(defn -main
  "Run all the tutorials"
  [& _]
  (doseq [file (examples-seq)]
    (println "\n;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;")
    (println ";; Transcript for " file)
    (transcript (read-all (io/reader file)))))
