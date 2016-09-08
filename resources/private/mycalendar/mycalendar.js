;(function() {
  "use strict";

  function MyCalendarsModel() {
    var self = this;
    self.calendars = ko.observableArray([]);
    self.noCalendarsFound = ko.observable(false);
    self.selectedCalendar = ko.observable();
    self.selectedCalendarId = ko.observable();
    self.reservationTypes = ko.observableArray();

    self.calendarNotificationsByDay = ko.observableArray([]);

    self.viewMode = ko.observable("calendar");

    self.setViewMode = function(mode) {
      if (mode === "list") {
        self.calendarNotificationsByDay([]);
        hub.send("calendarService::fetchUnseenUpdates");
      }
      self.viewMode(mode);
    }

    hub.subscribe("calendarService::myCalendarsFetched", function(event) {
      self.calendars(event.calendars);
      if (event.calendars.length > 0) {
        self.selectedCalendar(event.calendars[0]);
        self.noCalendarsFound(false);
      } else {
        self.selectedCalendar(undefined);
        self.noCalendarsFound(true);
      }
    });

    hub.subscribe("calendarService::unseenUpdatesFetched", function(event) {
      var notifications = _.map(event.updates,
        function(n) {
          n.acknowledged = "none";
          return ko.mapping.fromJS(n);
        });
      self.calendarNotificationsByDay(_.transform(
        _.groupBy(notifications, function(n) { return moment(n.startTime()).startOf("day").valueOf(); }),
        function (result, value, key) {
          return result.push({ day: _.parseInt(key), notifications: value });
        }, []));
    });

    self.selectedCalendar.subscribe(function(val) {
      self.reservationTypes(_.get(val, "reservationTypes", []));
      self.selectedCalendarId(_.get(val, "id", undefined));
    });

      self.appointmentParticipants = function(r) {
        return _.map(r.participants(), function (p) { return util.partyFullName(p); }).join(", ");
      };

  }

  $(function() {
    $("#mycalendar").applyBindings({ mycalendars: new MyCalendarsModel() });
  });

  if (features.enabled("ajanvaraus")) {
    hub.onPageLoad("mycalendar", function() {
      hub.send("calendarService::fetchMyCalendars");
    });
  }

})();
