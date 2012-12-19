(defproject com.datomic/datomic-sim "0.1.3"
  :description "Simulation testing with Datomic"
  :source-paths ["src" "examples"]
  :plugins [[lein-marginalia "0.7.1"]]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.0-beta2"]
                 [org.clojure/test.generative "0.3.0"]
                 [com.datomic/datomic-free "0.8.3664"
                  :exclusions [org.clojure/clojure]]])
