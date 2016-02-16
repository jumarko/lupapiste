LUPAPISTE.StatementService = function(params) {
  "use strict";
  var self = this;

  var application = params.application;
  var statements = ko.observableArray();

  self.statementId = params.statementId;
  self.applicationId = ko.pureComputed(function() { return util.getIn(application, ["id"]);});
  self.applicationTitle = ko.pureComputed(function() { return util.getIn(application, ["title"]);});

  self.data = ko.pureComputed(function() {return statements()[self.statementId()];});

  self.submitAllowed = {
    statement: ko.observable(false), 
    reply: ko.observable(false), 
    "reply-request": ko.observable(true)};

  self.commands = {
    statement: {saveDraft: "save-statement-as-draft", submit: "give-statement"},
    reply: {saveDraft: "save-statement-reply-as-draft", submit:"reply-statement"},
    "reply-request": {submit: "request-for-statement-reply"}};

  var saving = ko.observable(false);
  var submitId = ko.observable(null);
  var modifyId = ko.observable(util.randomElementId());

  var draftTimerId;

  var doSubmit = ko.pureComputed(function() {
    return !saving() && !!submitId();
  });

  ko.computed(function() {
    statements(_(util.getIn(application, ["statements"]))
      .map(function(statement) {
        return [statement.id, ko.mapping.fromJS(_.extend({
          "modify-id": "",
          person: {},
          text: null,
          status: null,
          reply: { saateText: null,
                   text: null,
                   nothingToAdd: null }
        }, statement))];
      })
      .zipObject()
      .value());
  });

  var doSubmitSubscription = doSubmit.subscribe(function() {
    var sid = submitId();
    if (!saving() && sid.statementId && sid.tab) {
      submit(sid.statementId, sid.tab);
    }
  });

  function updateModifyId(statementId) {
    statements()[statementId]["modify-id"](modifyId());
    modifyId(util.randomElementId());
  }

  function getCommandParams(statementId, tab) {
    var statement = statements()[statementId];
    var params = {
      statement: {text: util.getIn(statement, ["text"]), status: util.getIn(statement, ["status"])},
      reply: {text: util.getIn(statement, ["reply", "text"]), "nothing-to-add": util.getIn(statement, ["reply", "nothing-to-add"])},
      "reply-request": {text: util.getIn(statement, ["reply", "saateText"])}
    };
    return _.extend({
      id: self.applicationId(),
      "modify-id": modifyId(),
      "prev-modify-id": util.getIn(statement, ["modify-id"], ""),
      statementId: self.statementId(),
      lang: loc.getCurrentLanguage()
    }, _.pick(params[tab], _.identity));
  }

  function submit(statementId, tab) {
    var params = getCommandParams(statementId, tab);
    saving(true);
    clearTimeout(draftTimerId);
    ajax
      .command(util.getIn(self.commands, [tab, "submit"]), params)
      .success(function() {
        updateModifyId(statementId);
        pageutil.openApplicationPage({id: self.applicationId()}, "statement");
        repository.load(self.applicationId());
        hub.send("indicator-icon", {clear: true});
        hub.send("indicator", {style: "positive"});
        return false;
      })
      .error(function() {
        hub.send("indicator", {style: "negative"});
      })
      .fail(function() {
        hub.send("indicator", {style: "negative"});
      })
      .complete(function() {
        submitId(null);
        saving(false);
      })
      .call();
    return false;
  }

  function handleError(err) {
    var modificationConflict = err.text === "error.statement-updated-after-last-save";
    hub.send("show-dialog", {ltitle: "error.dialog.title",
                             component: "ok-dialog",
                             componentParams: {ltext: err.text,
                                               okFn: modificationConflict ? function() { repository.load(self.applicationId()); } : _.noop,
                                               okTitle: modificationConflict && loc("statement.refresh")}});
  }

  function updateDraft(statementId, tab) {
    var params = getCommandParams(statementId, tab),
        commandName = util.getIn(self.commands, [tab, "saveDraft"]);
    if (!commandName) {return;}
    saving(true);
    ajax
      .command(commandName, params)
      .success(function() {
        updateModifyId(statementId);
        hub.send("indicator-icon", {style: "positive"});
        return false;
      })
      .error(handleError)
      .fail(handleError)
      .complete(function() {
        saving(false);
      })
      .call();
    return false;
  }

  var changedSubscription = hub.subscribe("statement::changed", function(e) {
    var statementId = self.statementId();
    statements(_.set(statements(), [statementId].concat(e.path).join("."), e.value));
    if (!draftTimerId && statementId && e.tab) {
      draftTimerId = _.delay(function() {
        updateDraft(statementId, e.tab);
        draftTimerId = null;
      }, 2000);
    }
  });

  var submitSubscription = hub.subscribe("statement::submit", function(e) {
    if (self.applicationId() === e.applicationId && self.statementId() === e.statementId) {
      submitId({statementId: self.statementId(), tab: e.tab});
      self.submitAllowed[e.tab](false);
    }
  });

  var submitAllowedSubscription = hub.subscribe("statement::submitAllowed", function(e) {
    self.submitAllowed[e.tab](e.value);
  });

  self.dispose = function() {
    hub.unsubscribe(changedSubscription);
    hub.unsubscribe(submitSubscription);
    hub.unsubscribe(submitAllowedSubscription);
    doSubmitSubscription.dispose();
  };
};
