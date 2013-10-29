LUPAPISTE.AddLinkPermitModel = function() {
  var self = this;
  self.dialogSelector = "#dialog-add-link-permit";

  self.appId = 0;
  self.propertyId = ko.observable("");
  self.kuntalupatunnus = ko.observable("");
  self.userSelectedLinkPermit = ko.observable("");
  self.appMatches = ko.observableArray([]);
  self.errorMessage = ko.observable(null);
  self.processing = ko.observable();
  self.pending = ko.observable();

  self.finalLinkPermit = ko.observable("");

  self.ok = ko.computed(function() {
    // XOR in javascript
    return (self.kuntalupatunnus() || self.userSelectedLinkPermit()) &&
           !(self.kuntalupatunnus() && self.userSelectedLinkPermit());
  });

  self.onError = function(resp) {
    self.errorMessage(resp.text);
  };

  var getAppMatchesForLinkPermits = function(app) {
    ajax.query("app-matches-for-link-permits", {id: app.id()})
      .processing(self.processing)
      .pending(self.pending)
      .success(function(data) {
        self.errorMessage(null);
        self.appMatches(data["app-links"]);
      })
      .error(self.onError)
      .call();
    return false;
  };

  self.reset = function(app) {
    self.appId = app.id();
    self.propertyId(app.propertyId());
    self.kuntalupatunnus("");
    self.userSelectedLinkPermit("");
    self.finalLinkPermit("");
    self.errorMessage(null);
    self.processing(false);
    self.pending(false);

    getAppMatchesForLinkPermits(app);
  };


  //
  // Concurrency control    *** TODO: Onko talle tarvetta? ***
  //

//  self.requestContext = new RequestContext();
//  self.beginUpdateRequest = function() {
//    self.errorMessage(null);
//    self.requestContext.begin();
//    return self;
//  };


  self.addLinkPermit = function() {
    //
    // *** TODO: Miten lupatunnus selvitetään? ***
    //
    var lupatunnus = self.userSelectedLinkPermit() || self.kuntalupatunnus();
    var data = {id: self.appId, linkPermitId: lupatunnus, propertyId: self.propertyId()};
    ajax.command("add-link-permit", data)
      .processing(self.processing)
      .pending(self.pending)
      .success(function() {
        self.finalLinkPermit(lupatunnus);
        self.errorMessage(null);
        repository.load(self.appId);   // TODO: Mihin tata tarvitaan?
        LUPAPISTE.ModalDialog.close();
      })
      .error(self.onError)
      .call();
    return false;
  };


  //Open the dialog

  self.openAddLinkPermitDialog = function(app) {
    self.reset(app);
    LUPAPISTE.ModalDialog.open(self.dialogSelector);
  };

};
