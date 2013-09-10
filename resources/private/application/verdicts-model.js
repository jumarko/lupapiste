LUPAPISTE.VerdictsModel = function() {
  var self = this;

  function getApplicationId(bindings) {
    return bindings.application.id();
  }

  self.verdicts = ko.observable();
  self.response = ko.observable();

  self.refresh = function(application) {
    var manuallyUploadedAttachments = _.filter(application.attachments, function(attachment) {return _.isEqual(attachment.target, {type: "verdict"});});
    var verdicts = _.cloneDeep(application.verdicts)
                    .map(function(verdict) {

                      var poytakirjat = _.map(verdict.poytakirjat, function(pk) {
                        var myAttachments = filter(application.attachments, function(attachment) {return false;}) || []; // TODO
                        pk.attachments = myAttachments;
                        if (manuallyUploadedAttachments) {
                          pk.attachments = pk.attachments.concat(manuallyUploadedAttachments);
                          manuallyUploadedAttachments = null;
                        }
                      });

                      verdict.poytakirjat = poytakirjat;
                      return verdict;
                    });

    self.verdicts(verdicts);
  };

  self.openVerdict = function(bindings) {
    window.location.hash = "#!/verdict/" + getApplicationId(bindings);
    return false;
  };

  self.checkVerdict = function(bindings){
    ajax.command("check-for-verdict", {id: getApplicationId(bindings)})
    .success(function(resp) {
      self.response(JSON.stringify(resp.response));
    })
    .call();
  };
};
