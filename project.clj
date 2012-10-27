(defproject datomic-sim "1.0.0-SNAPSHOT"
  :description "Simulation testing with Datomic"
  :dependencies [[org.clojure/clojure "1.5.0-beta1"]
                 [com.datomic/datomic-free "0.8.3561"
                  :exclusions [org.clojure/clojure]]])
