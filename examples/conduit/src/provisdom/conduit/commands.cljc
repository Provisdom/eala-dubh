(ns provisdom.conduit.commands
  (:require [clojure.spec.alpha :as s]
            [lambdaisland.uniontypes #?(:clj :refer :cljs :refer-macros) [case-of]]
            [net.cgrand.xforms :as xforms]
            [provisdom.conduit.specs :as specs]
            [provisdom.maali.rules #?(:clj :refer :cljs :refer-macros) [defsession] :as rules]
            [provisdom.maali.tracing :as tracing]
            [provisdom.conduit.rules :as conduit]))

;;; Model command specs
(s/def ::init (s/cat :command #{:init} :init-session rules/session?))
(s/def ::upsert (s/cat :command #{:upsert} :old-value (s/nilable ::specs/Entity) :new-value (s/nilable ::specs/Entity)))
(s/def ::pending (s/cat :command #{:pending} :request ::specs/request))
(s/def ::response (s/cat :command #{:response} :response ::specs/Response))
(s/def ::page (s/cat :command #{:page} :page ::specs/page))
(s/def ::new-comment (s/cat :command #{:new-comment} :body ::specs/body))
(s/def ::delete-comment (s/cat :command #{:delete-comment} :id ::specs/id))
(s/def ::new-article (s/cat :command #{:new-article} :article ::specs/NewArticle))
(s/def ::update-article (s/cat :command #{:update-article} :article ::specs/UpdatedArticle))
(s/def ::delete-article (s/cat :command #{:delete-article} :article ::specs/DeletedArticle))
(s/def ::command (s/or ::init ::init
                       ::upsert ::upsert
                       ::pending ::pending
                       ::response ::response
                       ::page ::page
                       ::new-comment ::new-comment
                       ::delete-comment ::delete-comment
                       ::new-article ::new-article
                       ::update-article ::update-article
                       ::delete-article ::delete-article))

;;; Reduction function to update clara session state
(defn handle-state-command
  [session command]
  (case-of ::command command
           ::init {:keys [init-session]} init-session
           ::upsert {:keys [spec old-value new-value]} (let [[old-spec old-value] old-value
                                                             [new-spec new-value] new-value]
                                                         (when (and old-spec new-spec (not= old-spec new-spec))
                                                           (throw (ex-info (str "Upsert specs must match: " old-spec new-spec)
                                                                           {:command command})))
                                                         (rules/upsert session (or old-spec new-spec) old-value new-value))
           ::pending {:keys [request]} (rules/insert session ::specs/Pending {::specs/request request})
           ::response {:keys [response]} (rules/insert session ::specs/Response response)
           ::page {:keys [page]} (rules/upsert-q session ::specs/ActivePage
                                                 (rules/query-fn ::conduit/active-page :?active-page)
                                                 merge {::specs/page (s/unform ::specs/page page)})
           ::new-comment {:keys [body]} (rules/insert session ::specs/NewComment {:body body})
           ::delete-comment {:keys [id]} (rules/insert session ::specs/DeletedComment {:id id})
           ::new-article {:keys [article]} (rules/insert session ::specs/NewArticle article)
           ::update-article {:keys [article]} (rules/insert session ::specs/UpdatedArticle article)
           ::delete-article {:keys [article]} (rules/insert session ::specs/DeletedArticle article)))

(s/fdef handle-state-command
        :args (s/cat :session rules/session? :command ::command)
        :ret rules/session?)

(def update-state (fn [session command]
                    (-> session
                        (handle-state-command command)
                        (rules/fire-rules))))
(def debug-update-state (fn [session command]
                          (if session
                            (-> session
                                (tracing/with-tracing)
                                (handle-state-command command)
                                (rules/fire-rules)
                                (tracing/print-trace))
                            (-> session
                                (handle-state-command command)
                                (rules/fire-rules)))))
(def update-state-xf (comp (xforms/reductions update-state nil) (drop 1)))
(def debug-update-state-xf (comp (xforms/reductions debug-update-state nil) (drop 1)))
