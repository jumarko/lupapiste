(ns lupapalvelu.ui.printing-order.state)

(def empty-component-state {:attachments []
                            :tagGroups   []
                            :order       {}
                            :phase       2
                            :id          nil})

(defonce component-state (atom empty-component-state))

(defn will-unmount [& _]
  (reset! component-state empty-component-state))

(defn proceed-phase2 []
  (swap! component-state assoc :phase 2))