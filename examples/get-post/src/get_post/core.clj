(ns get-post.core
  (:require [org.httpkit.server :as server]
            [clj-http.client :as client]
            [plumbing.core :refer [defnk]]
            [kekkonen.cqrs :as cqrs]
            [kekkonen.core :as k]
            [schema.core :as s]))

(s/defschema GetInput
  {:name                         s/Str
   (s/optional-key :description) s/Str})

; GET http://localhost:3000/api/get-and-post?name=taras
(s/defn get-handler
  "Echoes a GetInput"
  [data :- GetInput]
  ; here is your handler
  (cqrs/success data))

(s/defschema PostInput
  {:data s/Str})

; POST http://localhost:3000/api/get-and-post {"data":"taras"}
(s/defn post-handler
  "Echoes a PostInput"
  [data :- PostInput]
  ; here is your handler
  (cqrs/success data))

(defnk ^:query simple-get
  "handles get"
  [data request]
  (cqrs/success data))

(defnk ^:get-post get-and-post
  "handles both requests"
  [get-params post-params request]
  (if (= (:request-method request) :get)
    ; note - with-fn-validation is not thread safe
    (s/with-fn-validation (get-handler get-params))
    (s/with-fn-validation (post-handler post-params))))

(defn interceptor [ctx]
  "logs incoming requests for us"
  (let [uri (get-in ctx [:request :uri])
        request-method (name (get-in ctx [:request :request-method]))]
    (println (str request-method ": " uri))
    #_(clojure.pprint/pprint (:request ctx))
    ctx))

(defn err-handler [ex data req]
  "logs exception message and return info to client"
  (println (str "ERROR: " (.getMessage ex)))
  (cqrs/failure (.getMessage ex)))

(def app (cqrs/cqrs-api {:core {:handlers      {:api [#'get-and-post
                                                      #'simple-get]}
                                :type-resolver (k/type-resolver :get-post :command :query)}
                         :mw   {:exceptions {:handlers {:schema.core/error err-handler}}}
                         :ring {:types        {:get-post {:methods    #{:get :post}
                                                          ; :query-params comes from Ring https://github.com/ring-clojure/ring/wiki/Parameters
                                                          :parameters {[:get-params]  [:request :query-params]
                                                                       [:post-params] [:request :body-params]}}}
                                :interceptors [interceptor]}}))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn -main [& args]
  (reset! server (server/run-server #'app {:port 3000}))
  (println "server running in port 3000")
  (client/get "http://localhost:3000/api/simple-get")
  (client/get "http://localhost:3000/api/get-and-post?name=taras")
  (client/post "http://localhost:3000/api/get-and-post" {:body "{\"data\":\"taras\"}"
                                                                                :content-type :json})
  (println "Schema validation demonstration:")
  (client/get "http://localhost:3000/api/get-and-post?nameWWW=taras"))

(defn run-server []
  (reset! server (server/run-server #'app {:port 3000}))
  (println "server running in port 3000"))