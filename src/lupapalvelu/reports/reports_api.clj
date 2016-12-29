(ns lupapalvelu.reports.reports-api
  (:require [taoensso.timbre :refer [error]]
            [sade.core :refer :all]
            [sade.util :as util]
            [clj-time.core :as ctime]
            [clj-time.coerce :as ccoerce]
            [lupapalvelu.action :as action :refer [defraw]]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.user :as usr]
            [lupapalvelu.reports.applications :as app-reports]))

(defraw open-applications-xlsx
  {:user-roles #{:authorityAdmin}}
  [{user :user {lang :lang} :data}]
  (let [orgId               (usr/authority-admins-organization-id user)
        excluded-operations [:tyonjohtajan-nimeaminen :tyonjohtajan-nimeaminen-v2]
        resulting-file-name (str (i18n/localize lang "applications.report.open-applications.file-name")
                                 "_"
                                 (util/to-xml-date (now))
                                 ".xlsx")]
    (try
      {:status  200
       :headers {"Content-Type"        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                 "Content-Disposition" (str "attachment;filename=\"" resulting-file-name "\"")}
       :body    (app-reports/open-applications-for-organization-in-excel! orgId lang excluded-operations)}
      (catch Exception e#
        (error "Exception while compiling open applications excel:" e#)
        {:status 500}))))

(defn validate-startTs [{{:keys [startTs]} :data}]
  (let [month-last-year (->> (util/get-timestamp-ago :year 1)
                             ccoerce/from-long
                             ctime/first-day-of-the-month
                             ctime/with-time-at-start-of-day
                             ccoerce/to-long)]
    (when (< (util/to-long startTs) month-last-year)
      (fail :error.too-long-in-past))))

(defraw applications-between-xlsx
  {:description "Excel with applications that have been submitted between given timeperiod"
   :parameters       [startTs endTs]
   :input-validators [(partial action/numeric-parameters [:startTs :endTs])
                      validate-startTs]
   :user-roles       #{:authorityAdmin}}
  [{user :user {lang :lang} :data}]
  (let [orgId               (usr/authority-admins-organization-id user)
        excluded-operations [:tyonjohtajan-nimeaminen :tyonjohtajan-nimeaminen-v2 :aiemmalla-luvalla-hakeminen]
        resulting-file-name (str (i18n/localize lang "applications.report.applications-between.file-name")
                                 "_"
                                 (util/to-xml-date (now))
                                 ".xlsx")]
    (try
      {:status  200
       :headers {"Content-Type"        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                 "Content-Disposition" (str "attachment;filename=\"" resulting-file-name "\"")}
       :body    (app-reports/applications-between-excel orgId
                                                        (util/to-long startTs)
                                                        (util/to-long endTs)
                                                        lang
                                                        excluded-operations)}
      (catch Exception e#
        (error "Exception while compiling open applications excel:" e#)
        {:status 500}))))