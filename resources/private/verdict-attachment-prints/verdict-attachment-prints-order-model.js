LUPAPISTE.VerdictAttachmentPrintsOrderModel = function() {
  "use strict";
  var self = this;
  self.dialogSelector = "#dialog-verdict-attachment-prints-order";
  self.application = null;

  self.processing = ko.observable(false);
  self.pending = ko.observable(false);
  self.errorMessage = ko.observable("");
  self.attachments = ko.observable([]);

  self.ordererOrganization = ko.observable("");
  self.ordererAddress = ko.observable("");
  self.ordererEmail = ko.observable("");
  self.ordererPhone = ko.observable("");
  self.applicantName = ko.observable("");
  self.kuntalupatunnus = ko.observable("");
  self.propertyId = ko.observable("");
  self.lupapisteId = ko.observable("");
  self.address = ko.observable("");

  self.authorizationModel = authorization.create();

  self.ok = ko.computed(function() {
    var attachmentOrderCountsAreNumbers = _.every(self.attachments(), function(a) {
      return util.isNum(a.orderAmount());
    });
    var dialogFieldValues = [self.ordererOrganization(),
                             self.ordererEmail(),
                             self.ordererPhone(),
                             self.applicantName(),
                             self.kuntalupatunnus(),
                             self.propertyId(),
                             self.lupapisteId(),
                             self.address()];
    var nonEmptyFields = _.every(dialogFieldValues, function(v){ return !_.isEmpty(v); });
    return self.authorizationModel.ok("order-verdict-attachment-prints") &&
           !self.processing() &&
           attachmentOrderCountsAreNumbers &&
           nonEmptyFields &&
           util.isValidEmailAddress(self.ordererEmail());
  });

  function enrichAttachment(a) {
    var versions = _(a.versions).reverse().value();
    var latestVersion = versions[0];
    a.filename = latestVersion.filename;
    a.contents = a.contents || loc(["attachmentType", a.type["type-group"], a.type["type-id"]])
    a.orderAmount = ko.observable("2");
    return a;
  }

  // Open the dialog

  self.init = function(bindings) {
    self.application = ko.toJS(bindings.application);
    self.processing(false);
    self.pending(false);
    self.errorMessage("");

    var attachments = _(self.application.attachments || [])
                      .filter(function(a) { return a.forPrinting && a.versions && a.versions.length; })
                      .map(enrichAttachment)
                      .value();
    self.attachments(attachments);

    var kopiolaitosMeta = self.application.organizationMeta.kopiolaitos;
    var currentUserName = currentUser.get().firstName() + " " + currentUser.get().lastName();
    var ordererName = (self.application.organizationName || "") + ", " + currentUserName;

    self.ordererOrganization(ordererName);
    self.ordererAddress(kopiolaitosMeta.kopiolaitosOrdererAddress || "");
    self.ordererEmail(kopiolaitosMeta.kopiolaitosOrdererEmail || "");
    self.ordererPhone(kopiolaitosMeta.kopiolaitosOrdererPhone || "");
    self.applicantName(self.application.applicant || "");
    self.kuntalupatunnus((self.application.verdicts && self.application.verdicts[0] && self.application.verdicts[0].kuntalupatunnus) ? self.application.verdicts[0].kuntalupatunnus : "");
    self.propertyId(self.application.propertyId);
    self.lupapisteId(self.application.id);
    self.address(self.application.address);

    self.authorizationModel.refresh(self.application.id);
  };

  self.openDialog = function(bindings) {
    self.init(bindings);
    LUPAPISTE.ModalDialog.open(self.dialogSelector);
  };

  // Send the prints order

  function normalizeAttachments(attachments) {
    return _.map(attachments, function(a) {
      a.amount = a.orderAmount();
      return _.pick(a, ["forPrinting", "amount", "contents", "type", "versions", "filename"]);
    });
  }

  self.orderAttachmentPrints = function() {
    var data = {
      id: self.application.id,
      lang: loc.getCurrentLanguage(),
      attachmentsWithAmounts: normalizeAttachments(self.attachments()),
      orderInfo: {
        ordererOrganization: self.ordererOrganization(),
        ordererAddress: self.ordererAddress(),
        ordererEmail: self.ordererEmail(),
        ordererPhone: self.ordererPhone(),
        applicantName: self.applicantName(),
        kuntalupatunnus: self.kuntalupatunnus(),
        propertyId: self.propertyId(),
        lupapisteId: self.lupapisteId(),
        address: self.address()
      }
    };

    ajax.command("order-verdict-attachment-prints", data)
      .processing(self.processing)
      .pending(self.pending)
      .success(function() {
        var content = loc("verdict-attachment-prints-order.order-dialog.ready", self.attachments().length);
        LUPAPISTE.ModalDialog.showDynamicOk(loc("verdict-attachment-prints-order.order-dialog.title"), content);
        pageutil.showAjaxWait();
        repository.load(self.application.id);
      })
      .error(function(d) {
        error(d);
        self.errorMessage(d.text);
      })
      .call();
  };

};
