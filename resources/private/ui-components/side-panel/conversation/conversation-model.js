LUPAPISTE.ConversationModel = function(params) {
  "use strict";
  var self = this;

  self.application = params.application;
  self.authorization = params.authorization;

  self.comment = comments.create();

  self.authorities = [];
  self.mainConversation = ko.observable(true);
  self.currentPage = lupapisteApp.models.rootVMO.currentPage;

  self.infoRequest = ko.pureComputed(function() {
    return util.getIn(self, ["application", "infoRequest"]);
  });

  self.applicationId = ko.computed(function() {
    return util.getIn(self, ["application", "id"]);
  });

  function refreshConversations(page) {
    if (page) {
      var type = pageutil.getPage();
      self.mainConversation(true);
      switch(type) {
        case "attachment":
          self.mainConversation(false);
          self.comment.refresh(self.application, false, {type: type, id: pageutil.lastSubPage()});
          break;
        case "statement":
          self.mainConversation(false);
          self.comment.refresh(self.application, false, {type: type, id: pageutil.lastSubPage()});
          break;
        case "verdict":
          self.mainConversation(false);
          self.comment.refresh(self.application, false, {type: type, id: pageutil.lastSubPage()}, ["authority"]);
          break;
        default:
          self.comment.refresh(self.application, true);
          break;
      }
    }
  }

  ko.computed(function() {
    refreshConversations(self.currentPage());
  }).extend({ rateLimit: 100 });

  var previousPage = self.currentPage();

  var pageLoadSubscription = hub.subscribe({type: "page-load"}, function(data) {
    if (self.currentPage() !== previousPage && self.comment.text()) {
      hub.send("show-dialog", {ltitle: "application.conversation.unsentMessage.header",
                               size: "medium",
                               component: "yes-no-dialog",
                               componentParams: {ltext: "application.conversation.unsentMessage",
                                                 yesFn: function() {
                                                   if (data.previousHash) {
                                                     location.hash = data.previousHash;
                                                   }
                                                   // HIGHlight conversation
                                                 },
                                                 noFn: function() {
                                                   self.comment.text(undefined);
                                                   previousPage = self.currentPage();
                                                 },
                                                 lyesTitle: "application.conversation.sendMessage",
                                                 lnoTitle: "application.conversation.clearMessage"}});
    } else {
      previousPage = self.currentPage();
    }
  });

  self.dispose = function() {
    hub.unsubscribe(pageLoadSubscription);
  };
};
