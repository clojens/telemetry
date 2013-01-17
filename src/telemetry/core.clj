(ns telemetry.core
  (:use [ring.middleware params keyword-params])
  (:require
   [clojure.string :as str]
   [swank.swank :as swank]
   (lamina [core :as lamina]
           [connections :as connection]
           trace)
   (gloss [core :as gloss])
   (aleph [trace :as trace]
          [formats :as formats]
          [http :as http]
          [tcp :as tcp])
   [compojure.core :refer [routes GET POST]]
   [flatland.useful.utils :refer [returning]]
   [flatland.useful.map :refer [keyed map-vals]])
  (:import java.util.Date)
  (:use flatland.useful.debug))

(def tcp-port 1845)
(def http-port 1846)
(def trace-port 1847)

(declare trace-router)

(defonce endpoint
  (delay
    (trace/trace-endpoint {:client-options {:host "localhost" :port trace-port}})))

(defn input-handler [ch _]
  (lamina/receive-all ch
    (fn [[probe data]]
      (let [probe (str/replace probe #"\." ":")]
        (lamina.trace/trace* probe
          (formats/decode-json data))))))

(defn inspection-handler [req]
  (let [{:keys [q]} (:params req)]
    {:status 200
     :headers {"content-type" "application/json"}
     :body (->> (trace/subscribe @endpoint (formats/url-decode q))
                :messages
                (lamina/map* formats/encode-json->string)
                (lamina/map* #(str % "\n")))}))

(defn graphite-channel [host port]
  (tcp/tcp-client {:host host :port port
                   :frame [(gloss/string :utf-8 :delimiters [" "]) ;; message type
                           (gloss/string-integer :utf-8 :delimiters [" "]) ;; count
                           (gloss/string-integer :utf-8 :delimiters ["\n"])]})) ;; timestamp

(defn outgoing-channel-generator [host port]
  (connection/persistent-connection
   (fn []
     (doto @(graphite-channel host port)
       (lamina/ground))))) ;; we will only ever look at the write end of the channel

(declare outgoing-channel)

(defn unix-time [^Date date]
  (-> date (.getTime) (quot 1000)))

(def listeners (ref {}))

(defn add-listener [name query]
  (let [channel (doto (trace/subscribe @endpoint query)
                  (-> (:messages)
                      (->> (lamina/map* (fn [data]
                                          [name data (unix-time (Date.))])))
                      (lamina/siphon @(outgoing-channel))))
        unsubscribe (fn []
                      (lamina/close (:messages channel)))]
    (dosync
     (alter listeners assoc name (keyed [query channel unsubscribe])))
    {:status 204})) ;; no content

(defn remove-listener [name]
  (if-let [{:keys [unsubscribe query]} (dosync (returning (get @listeners name)
                                                 (alter listeners dissoc name)))]

    (do (unsubscribe)
        {:status 200 :headers {"content-type" "application/json"}
         :body (formats/encode-json->string {:query query})})
    {:status 404}))

(defn get-listeners []
  {:status 200 :headers {"content-type" "application/json"}
   :body (formats/encode-json->string (map-vals @listeners :query))})

(defn init
  ([] (init "localhost" 2003))
  ([graphite-host graphite-port]
     (def trace-router (trace/start-trace-router {:port trace-port}))
     (def outgoing-channel (outgoing-channel-generator graphite-host graphite-port))

     (def tcp-server
       (tcp/start-tcp-server
        input-handler
        {:port tcp-port
         :delimiters ["\r\n" "\n"]
         :frame [(gloss/string :utf-8 :delimiters [" "])
                 (gloss/string :utf-8)]}))

     (def http-server
       (http/start-http-server
        (-> (routes (GET "/inspect" {:as req}
                      (inspection-handler req))
                    (POST "/listen" [name query]
                      (add-listener name query))
                    (POST "/forget" [name]
                      (remove-listener name))
                    (GET "/listeners" []
                      (get-listeners)))
            wrap-keyword-params
            wrap-params
            http/wrap-ring-handler)

        {:port http-port}))

     (let [host "localhost" port 4005]
       (printf "Starting swank on %s:%d\n" host port)
       (swank/start-server :host host :port port))
     nil))

(defn stop []
  (tcp-server)
  (http-server))

(defn -main [& args]
  (init))
