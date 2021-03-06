LUPAPISTE.StatementEditModel = function(params) {
  "use strict";
  var self = this;

  ko.utils.extend( self, new LUPAPISTE.ComponentBaseModel());

  self.tab = "statement";

  self.authModel = params.authModel;

  var applicationId = params.applicationId;

  self.applicationTitle = params.applicationTitle;
  self.data = params.data;
  self.target = params.target;

  self.selectedStatus = ko.observable();
  self.text = ko.observable();
  self.inAttachment = ko.observable();

  var initSubscription = self.data.subscribe(function() {
    self.selectedStatus(util.getIn(self.data, ["status"]));
    self.text(util.getIn(self.data, ["text"]));
    self.inAttachment(util.getIn(self.data, ["in-attachment"]));
  });

  var commands = params.commands;

  self.statuses = ko.observableArray([]);

  function dataView( target ) {
    return ko.mapping.toJS( _.pick( ko.unwrap( target ), ["id", "type"]));
  }

  self.attachments = self.disposedPureComputed( function() {
    return _.filter(lupapisteApp.services.attachmentsService.attachments(),
      function(attachment) {
        return _.isEqual(dataView(attachment().target), dataView(self.target));
      });
  });

  function attachmentsContainsStatement() {
    return _.some(self.attachments(), function(a) {
      var type = util.getIn(a, ["type"]);
      return _.isEqual(type, {"type-group": "ennakkoluvat_ja_lausunnot", "type-id": "lausunto"});
    });
  }

  var submitAllowed = ko.pureComputed(function() {
    var inAttachmentOk = self.inAttachment() && self.attachments().length > 0 && attachmentsContainsStatement();
    return !!self.selectedStatus() && (inAttachmentOk || (!self.inAttachment() && !!self.text() && !_.isEmpty(self.text())));
  });

  self.showAttachmentGuide = self.disposedPureComputed(function() {
    return self.inAttachment() && (self.attachments().length === 0 || !attachmentsContainsStatement());
  });

  self.enabled = ko.pureComputed(function() {
    return self.authModel.ok(commands.submit);
  });

  self.isDraft = ko.pureComputed(function() {
    return _.includes(["requested", "draft"], util.getIn(self.data, ["state"]));
  });

  self.showStatement = ko.pureComputed(function() {
    return self.isDraft() ? self.enabled() : true;
  });

  self.coverNote = ko.pureComputed(function() {
    var canViewCoverNote = util.getIn(self.data, ["person", "userId"]) === lupapisteApp.models.currentUser.id() || lupapisteApp.models.currentUser.isAuthority();
    return self.tab === "statement" && canViewCoverNote ? util.getIn(self.data, ["saateText"]) : "";
  });

  var textSubscription = self.text.subscribe(function(value) {
    if(util.getIn(self.data, ["text"]) !== value) {
      hub.send("statement::changed", {tab: self.tab, path: ["text"], value: self.text()});
    }
  });

  var statusSubscription = self.selectedStatus.subscribe(function(value) {
    if(util.getIn(self.data, ["status"]) !== value) {
      hub.send("statement::changed", {tab: self.tab, path: ["status"], value: self.selectedStatus()});
    }
  });

  var inAttachmentSub = self.inAttachment.subscribe(function(value) {
    if(util.getIn(self.data, ["in-attachment"]) !== value) {
      hub.send("statement::changed", {tab: self.tab, path: ["in-attachment"], value: self.inAttachment()});
    }
  });

  hub.send("statement::submitAllowed", {tab: self.tab, value: submitAllowed()});

  var submitSubscription = submitAllowed.subscribe(function(value) {
    hub.send("statement::submitAllowed", {tab: self.tab, value: value});
  });

  function initStatementStatuses(appId) {
    ajax
    .query("get-possible-statement-statuses", {id: appId})
    .success(function(resp) {
      var sorted = _(resp.data)
        .map(function(item) { return {id: item, name: loc(["statement", item])}; })
        .sortBy("name")
        .value();
      self.statuses(sorted);
    })
    .call();
  }

  if (applicationId()) {
    initStatementStatuses(applicationId());
  }
  var initApplicationSubscription = applicationId.subscribe(function(appId) {
    if (appId) {
      initStatementStatuses(appId);
    }
  });

  self.dispose = function() {
    if (initApplicationSubscription)  {
      initApplicationSubscription.dispose();
    }
    initSubscription.dispose();
    textSubscription.dispose();
    statusSubscription.dispose();
    submitSubscription.dispose();
    inAttachmentSub.dispose();
  };
};
