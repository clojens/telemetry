(ns flatland.telemetry
  (:gen-class))

(defn -main
  "Starts up a basic telemetry server with all the default settings and a carbon module."
  [& args]
  (require '[flatland.telemetry.server])
  (apply (resolve 'flatland.telemetry.server/-main) args))
