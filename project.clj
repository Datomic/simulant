(defproject com.datomic/datomic-sim "0.1.3"
  :description "Simulation testing with Datomic"
  :dev-dependencies [[marginalia "0.3.2"]]
  :source-paths ["src" "examples"]
  :dependencies [[org.clojure/clojure "1.5.0-beta2"]
                 [org.clojure/test.generative "0.3.0"]
                 [com.datomic/datomic-free "0.8.3664"
                  :exclusions [org.clojure/clojure]]])
