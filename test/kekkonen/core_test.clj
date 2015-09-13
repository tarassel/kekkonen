(ns kekkonen.core-test
  (:require [kekkonen.core :as k]
            [kekkonen.midje :refer :all]
            [midje.sweet :refer :all]
            [schema.core :as s]
            [plumbing.core :as p]))

;;
;; test handlers
;;

(p/defnk ^:handler ping [] "pong")

(p/defnk ^:handler get-items :- #{s/Str}
  [[:components db]] @db)

(p/defnk ^:handler add-item! :- #{s/Str}
  "Adds an item to database"
  [[:data item :- String]
   [:components db]]
  (swap! db conj item))

(p/defnk ^:handler reset-items! :- #{s/Str}
  "Resets the database"
  [[:components db]]
  (swap! db empty))

(p/defnk ^:handler plus
  [[:data x :- s/Int, y :- s/Int]]
  (+ x y))

(s/defschema User
  {:name s/Str
   :address {:street s/Str
             :zip s/Int
             (s/optional-key :country) (s/enum :FI :CA)}})

(p/defnk ^:handler echo :- User
  "Echoes the user"
  {:roles #{:admin :user}}
  [data :- User]
  data)

;;
;; facts
;;

(fact "action-kws"
  (k/action-kws :api.user/add-user!) => [:api :user :add-user!]
  (k/action-kws :api.user/user.api) => [:api :user :user.api]
  (k/action-kws :swagger.json) => [:swagger.json])

(fact "using services directly"

  (fact "simple query works"
    (ping {}) => "pong")

  (fact "stateful services"
    (let [db (atom #{})]

      (fact "call fails with missing dependencies"
        (get-items {}) => throws?)

      (fact "call with dependencies set succeeds"
        (get-items {:components {:db db}}) => #{})

      (fact "call with dependencies with extra data succeeds"
        (get-items {:components {:db db}
                    :EXTRA {:kikka :kukka}
                    :data {}}) => #{})

      (fact "call with wrong types succeeds without validation set"
        (add-item! {:components {:db db}
                    :data {:item 123}}) => #{123}
        (reset-items! {:components {:db db}}) => #{})

      (fact "call with wrong types succeeds without validation set"
        (s/with-fn-validation
          (add-item! {:components {:db db}
                      :data {:item 123}})) => throws?)

      (fact "call with right types succeeds with validation set"
        (s/with-fn-validation
          (add-item! {:components {:db db}
                      :data {:item "kikka"}})) => #{"kikka"}
        (get-items {:components {:db db}}) => #{"kikka"}))))

;;
;; Collecting services
;;

(fact "resolving types"
  (fact "successfull resolution"
    ((k/type-resolver :kikka) {:kikka true}) => {:type :kikka}
    ((k/type-resolver :kikka :kukka) {:kukka true}) => {:type :kukka})
  (fact "unsuccessfull resolution"
    ((k/type-resolver :kikka) {}) => nil
    ((k/type-resolver :kikka :kukka) {}) => nil))

(fact "collecting handlers"
  (s/with-fn-validation

    (fact "anonymous functions"

      (fact "fn"

        (fact "with default type"
          (k/collect
            (k/handler
              {:name :echo
               :input {:name s/Str}}
              identity)) => (just
                              {:echo
                               (just
                                 {:function fn?
                                  :description ""
                                  :user {}
                                  :type :handler
                                  :name :echo
                                  :input {:name s/Str}
                                  :output s/Any})}))

        (fact "type can be overridden"
          (k/collect
            (k/handler
              {:name :echo
               :type :kikka}
              identity)
            k/any-type-resolver) => (just
                                      {:echo
                                       (contains
                                         {:type :kikka})})))

      (fact "fnk"
        (k/collect
          (k/handler
            {:name :echo
             :description "Echoes the user"
             :query true
             :roles #{:admin :user}}
            (p/fnk f :- User [data :- User] data))) => (just
                                                         {:echo
                                                          (just
                                                            {:function fn?
                                                             :type :handler
                                                             :name :echo
                                                             :user {:query true
                                                                    :roles #{:admin :user}}
                                                             :description "Echoes the user"
                                                             :input {:data User
                                                                     s/Keyword s/Any}
                                                             :output User})}))

      (fact "with unresolved type"
        (let [handler (k/handler
                        {:name :echo
                         :input {:name s/Str}}
                        identity)]
          (k/collect
            handler
            {:type-resolver (k/type-resolver :ILLEGAL)}) => (throws? {:target handler}))))

    (fact "Var"
      (fact "with resolved type"
        (k/collect #'echo) => (just
                                {:echo
                                 (just
                                   {:function fn?
                                    :type :handler
                                    :name :echo
                                    :user {:roles #{:admin :user}}
                                    :description "Echoes the user"
                                    :input {:data User
                                            s/Keyword s/Any}
                                    :output User
                                    :source-map (just
                                                  {:line 38
                                                   :column 1
                                                   :file string?
                                                   :ns 'kekkonen.core-test
                                                   :name 'echo})})}))

      (fact "with unresolved type"
        (k/collect #'echo {:type-resolver (k/type-resolver :ILLEGAL)}) => (throws? {:target #'echo})))

    (fact "Namespaces"
      (let [handlers (k/collect 'kekkonen.core-test)]

        (count handlers) => 6
        handlers => (just
                      {:ping k/handler?
                       :get-items k/handler?
                       :add-item! k/handler?
                       :reset-items! k/handler?
                       :echo k/handler?
                       :plus k/handler?})))))

(fact "registry"
  (s/with-fn-validation

    (fact "can't be created without handlers"
      (k/create {}) => throws?)

    (fact "can't be created with root level handlers"
      (k/create {:handlers 'kekkonen.core-test}) => throws?)

    (fact "can be created with namespaced handlers"
      (k/create {:handlers {:test 'kekkonen.core-test}}) => truthy)

    (fact "with handlers and context"
      (let [k (k/create {:context {:components {:db (atom #{})}}
                         :handlers {:test 'kekkonen.core-test}})]

        (fact "all handlers"
          (count (k/all-handlers k)) => 6)

        (fact "non-existing action"
          (k/some-handler k :test/non-existing) => nil
          (k/invoke k :test/non-existing) => throws?)

        (fact "existing action contains :type, :ns and :action"
          (k/some-handler k :test/ping) => (contains {:type :handler, :ns :test, :action :test/ping})
          (k/invoke k :test/ping) => "pong")

        (fact "crud via registry"
          (k/invoke k :test/get-items) => #{}
          (k/invoke k :test/add-item! {:data {:item "kikka"}}) => #{"kikka"}
          (k/invoke k :test/get-items) => #{"kikka"}
          (k/invoke k :test/reset-items!) => #{}
          (k/invoke k :test/get-items) => #{}

          (fact "context-level overrides FTW!"
            (k/invoke k :test/get-items {:components {:db (atom #{"hauki"})}}) => #{"hauki"}))))

    (fact "lots of handlers"
      (let [k (k/create {:handlers
                         {:admin
                          {:kikka 'kekkonen.core-test
                           :kukka 'kekkonen.core-test}
                          :public 'kekkonen.core-test
                          :kiss #'ping
                          :abba [#'ping #'echo]
                          :wasp ['kekkonen.core-test]
                          :bon (k/handler
                                 {:name :jovi}
                                 (constantly :runaway))}})]

        (fact "deeply nested"
          (fact "namespaces can be joined with ."
            (k/some-handler k :admin.kikka/ping) => (contains {:action :admin.kikka/ping})
            (k/invoke k :admin.kikka/ping) => "pong")
          (fact "namespaces can be joined with /"
            (k/invoke k :admin/kukka/ping) => "pong")
          (fact "ns is set to handler with ."
            (k/some-handler k :admin.kukka/ping) => (contains {:ns :admin.kukka})))

        (fact "not nested"
          (k/invoke k :kiss/ping) => "pong")

        (fact "var"
          (k/invoke k :abba/ping) => "pong")

        (fact "vector of vars"
          (k/invoke k :wasp/ping) => "pong")

        (fact "vector of namespaces"
          (k/invoke k :wasp/ping) => "pong")

        (fact "handler"
          (k/invoke k :bon/jovi) => :runaway)))

    (fact "sub-context"
      (let [k (k/create {:handlers {:api #'plus}})]

        (k/invoke k :api/plus {}) => throws?
        (k/invoke k :api/plus {:data {:x 1}}) => throws?
        (k/invoke k :api/plus {:data {:x 1, :y 2}}) => 3

        (let [k (k/with-context k {:data {:x 1}})]
          (k/invoke k :api/plus {}) => throws?
          (k/invoke k :api/plus {:data {:x 1}}) => throws?
          (k/invoke k :api/plus {:data {:y 2}}) => 3)))))

(fact "user-meta"
  (let [k (k/create
            {:handlers {:api (k/handler
                               {:name :test
                                ::roles #{:admin}}
                               (p/fn-> :x))}
             :user {::roles (fn [context allowed-roles]
                              (let [role (::role context)]
                                (if (allowed-roles role)
                                  context
                                  (throw (ex-info
                                           "invalid role"
                                           {:role role
                                            :required allowed-roles})))))}})]

    (k/all-handlers k) => (just [anything])

    (k/invoke k :api/test {:x 1}) => (throws? {:role nil, :required #{:admin}})
    (k/invoke k :api/test {:x 1 ::role :user}) => (throws? {:role :user, :required #{:admin}})
    (k/invoke k :api/test {:x 1 ::role :admin}) => 1))

(fact "context transformations"
  (let [copy-ab-to-cd (k/context-copy [:a :b] [:c :d])
        remove-ab (k/context-dissoc [:a :b])]

    (copy-ab-to-cd {:a {:b 1}}) => {:a {:b 1} :c {:d 1}}
    (remove-ab {:a {:b 1}}) => {}
    ((comp remove-ab copy-ab-to-cd) {:a {:b 1}}) => {:c {:d 1}}))

(fact "transforming"
  (s/with-fn-validation
    (let [k (k/create {:handlers {:api (k/handler {:name :test} #(:y %))}
                       :transformers [(k/context-copy [:x] [:y])
                                      (k/context-dissoc [:x])]})]

      (fact "transformers are executed"
        (k/invoke k :api/test {:x 1}) => 1))))

(fact "transforming handlers"
  (fact "enriching handlers"
    (k/transform-handlers
      (k/create {:handlers {:api (k/handler {:name :test} identity)}})
      (fn [handler]
        (assoc handler :kikka :kukka)))

    => (contains
         {:handlers
          (just
            {:api
             (just
               {:test
                (contains
                  {:kikka :kukka})})})}))

  (fact "stripping handlers"
    (k/transform-handlers
      (k/create {:handlers {:api (k/handler {:name :test} identity)}})
      (constantly nil)) => (contains {:handlers {}})))

(fact "invoke-time extra data"
  (let [k (k/create {:handlers
                     {:api
                      [(k/handler
                         {:name :registry}
                         (partial k/get-registry))
                       (k/handler
                         {:name :handler
                          :description "magic"}
                         (partial k/get-handler))
                       (k/handler
                         {:name :names}
                         (fn [context]
                           (->> context
                                k/get-registry
                                k/all-handlers
                                (map :name))))]}})]

    (fact "::registry"
      (s/validate k/Registry (k/invoke k :api/registry)) => truthy)

    (fact "::handler"
      (k/invoke k :api/handler) => (contains {:description "magic"}))

    (fact "going meta boing boing"
      (k/invoke k :api/names) => [:registry :handler :names])))

; TODO: will override paths as we do merge
(fact "handlers can be injected into existing registry"
  (let [k (-> (k/create {:handlers {:api (k/handler {:name :test} identity)}})
              (k/inject (k/handler {:name :ping} identity)))]
    k => (contains
           {:handlers
            (just
              {:api (just
                      {:test anything})
               :ping anything})})))