(defproject jepsen.etcdemo "0.1.0-SNAPSHOT"
  :description "A Jepsen test for etcd"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :main jepsen.etcdemo
  :dependencies [[org.clojure/clojure "1.10.0"]

                 ; the version set in the tutorial has an issue with the
                 ; client/Client interface
                 [jepsen "0.1.12"]

                 [verschlimmbesserung "0.1.3"]]
  :repl-options {:init-ns jepsen.etcdemo})
