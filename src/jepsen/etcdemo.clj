(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [verschlimmbesserung.core :as v]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os :as os]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]
            [jepsen.etcdemo [set :as set]
                            [support :as s]]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(def rundir "/var/lib/etcd")
(def logfile "/var/log/etcd.log")

(defn db
  "Etcd DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing etcd" version)
      (c/su (c/exec "mkdir" "-p" rundir))
      (c/su (c/exec "chown" "etcd" rundir))
      (c/su (c/exec "systemctl" "start" "etcd"))
      (Thread/sleep 1000))

    (teardown! [_ test node]
      (info node "tearing down etcd")
      (c/su (c/exec "systemctl" "stop" "etcd"))
      (c/su (c/exec "rm" "-rf" rundir)))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (s/client-url node)
                                 {:timeout 5000})))

  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)]
      (try+
        (case (:f op)
          :read (let [value (-> conn 
                                  (v/get k {:quorum? (:quorum test)})
                                  parse-long)]
                    (assoc op :type :ok, :value (independent/tuple k value)))

          :write (do (v/reset! conn k v)
                    (assoc op :type :ok))

          :cas (let [[old new] v]
                  (assoc op :type (if (v/cas! conn k old new) :ok :fail))))

          (catch java.net.SocketTimeoutException ex
            (assoc op :type (if (= :read (:f op)) :fail :info), :error :timeout))

          (catch [:errorCode 100] ex
            (assoc op :type :fail, :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]
    ; If our connection were stateful, we'd close it here. Verschlimmmbesserung
    ; doesn't actually hold connections, so there's nothing to close.
    ))

(defn register-workload
   "Tests linearizable reads, writes, and compare-and-set operations on
  independent keys."
  [opts]
  {:client    (Client. nil)
   :checker   (independent/checker
                (checker/compose
                  {:linear   (checker/linearizable {:model     (model/cas-register)
                                                    :algorithm :linear})
                   :timeline (timeline/html)}))
   :generator (independent/concurrent-generator
                10
                (range)
                (fn [k]
                  (->> (gen/mix [r w cas])
                       (gen/limit (:ops-per-key opts)))))})

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"set" set/workload
   "register" register-workload})

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map.

  :quorum       Whether to use quorum reads
  :rate         Approximate number of requests per second, per thread
  :ops-per-key  Maximum number of operations allowed on any given key
  :workload     Type of workload."
  [opts]
  (let [quorum (boolean (:quorum opts))
        workload ((get workloads (:workload opts)) opts)]
    (merge tests/noop-test
          opts
          {:name (str "etcd q=" quorum " " (name (:workload opts)))
           :quorum quorum
           :os   os/noop
           :db   (db "v3.3.13")
           :client (:client workload)
           :nemesis (nemesis/partition-random-halves)
           :checker  (checker/compose
                       { :perf (checker/perf)
                         :workload (:checker workload)
                       })
          :generator (gen/phases
                        (->> (:generator workload)
                            (gen/stagger (/ (:rate opts)))
                            (gen/nemesis
                              (gen/seq (cycle [(gen/sleep 5)
                                                {:type :info, :f :start}
                                                (gen/sleep 5)
                                                {:type :info, :f :stop}])))
                            (gen/time-limit (:time-limit opts)))
                        (gen/log "Healing cluster")
                        (gen/nemesis (gen/once {:type :info, :f :stop}))
                        (gen/log "Waiting for recovery")
                        (gen/sleep 10)
                        (gen/clients (:final-generator workload)))
            })))

(def cli-opts
  "Additional command line options."
    [["-q" "--quorum" "Use quorum reads, instead of reading from any primary."]
     ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
    [nil "--ops-per-key NUM" "Maximum number of operations on any given key."
      :default  100
      :parse-fn parse-long
      :validate [pos? "Must be a positive integer."]]
    ["-w" "--workload NAME" "What workload should we run?"
    :missing  (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run!
    (merge (cli/single-test-cmd { :test-fn etcd-test
                                  :opt-spec cli-opts
                                })
           (cli/serve-cmd))
    args))
