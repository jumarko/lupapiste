// Attachments table icon column contents. Used both by the regular
// attachments table and attachment multiselect template.
LUPAPISTE.StateIconsModel = function( params ) {
  "use strict";
  var self = this;


  var attachment = ko.unwrap( params.attachment );

  var service    = lupapisteApp.services.attachmentsService;
  var transfers  = lupapisteApp.models.application._js.transfers;

  function sentToCaseManagement(attachment) {
    return attachment.sent && !_.isEmpty(_.filter(transfers, {type: "attachments-to-asianhallinta"}));
  }

  function sent(attachment) {
    return attachment.sent && !sentToCaseManagement(attachment);
  }

  function hasFile(attachment) {
    return _.get(attachment, "latestVersion.filename");
  }

  function canVouch(attachment) {
    return hasFile(attachment) && !service.isNotNeeded(attachment);
  }

  function versionString(m) {
    return [_.get(m, "version.major"), _.get(m, "version.minor")].join(".");
  }

  function signed(attachment) {
    return _(attachment.signatures).map(versionString).includes(versionString(attachment.latestVersion));
  }

  function missingFile(attachment) {
    return (!hasFile(attachment) && !attachment.notNeeded());
  }

  function rejected(attachment) {
    return service.isRejected(attachment) && canVouch(attachment);
  }

  function needsAttention(attachment) {
    return rejected(attachment) || missingFile(attachment);
  }

  function approved(attachment) {
    return service.isApproved(attachment) && canVouch(attachment);
  }

  function requiresAuthorityAction(attachment) {
    return service.requiresAuthorityAction(attachment);
  }

  function stamped(attachment) {
    return _.get(attachment, "latestVersion.stamped");
  }

  function forPrinting(attachment) {
    return ko.unwrap(attachment.forPrinting);
  }

  function notPublic(attachment) {
    return util.getIn(attachment, ["metadata", "nakyvyys"], "julkinen") !== "julkinen";
  }

  function archived(attachment) {
    return util.getIn(attachment, ["metadata", "tila"]) === "arkistoitu";
  }

  function approverInfo(attachment) {

      var approverInfo = "";
      var approval = service.attachmentFirstApproval( attachment );
      if (approval) {
          approverInfo = sprintf("%s %s:\n %s %s",
                                 loc(["document.approved"]),
                                 moment(approval.timestamp).format("D.M.YYYY HH:mm"),
                                 approval.user.firstName,
                                 approval.user.lastName);
      }

      return approverInfo;
  }

  function signerInfo(attachment) {
    return _(attachment .signatures)
           .filter(function( s ) {
             return versionString( s ) === versionString( attachment.latestVersion );
           })
           .map( function( s ) {
             return sprintf( "%s %s:\n %s %s",
                             loc(["attachment.signed"]),
                             moment(s.created).format( "D.M.YYYY HH:mm"),
                             s.user.firstName,
                             s.user.lastName);
           })
           .join( "\n");
  }

  function stamperInfo(attachment) {
      var stamping = service.stampingData(attachment);
      var info = "";
      if (stamping.isStamped) {
          info = sprintf("%s %s:\n %s %s",
                         loc(["stamp.comment"]),
                         moment(stamping.timestamp).format("D.M.YYYY HH:mm"),
                         stamping.user.firstName,
                         stamping.user.lastName);
      }
      return info;
  }

  self.stateIcons = function() {
    return _( [[approved,                {css: "lupicon-circle-check positive",
                                          icon: "approved",
                                          title: "",
                                          info: approverInfo(attachment)}],
               [needsAttention,          {css: "lupicon-circle-attention negative",
                                          icon: "rejected",
                                          title: "",
                                          info: ""}],
               [signed,                  {css: "lupicon-circle-pen positive",
                                          icon: "signed",
                                          title: "",
                                          info: signerInfo(attachment)}],
               [requiresAuthorityAction, {css: "lupicon-circle-star primary",
                                          icon: "state",
                                          title: "",
                                          info: ""}],
               [stamped,                 {css: "lupicon-circle-stamp positive",
                                          icon: "stamped",
                                          title: "",
                                          info: stamperInfo(attachment)}],
               [sent,                    {css: "lupicon-circle-arrow-up positive",
                                          icon: "sent",
                                          title: "",
                                          info: ""}],
               [sentToCaseManagement,    {css: "lupicon-circle-arrow-up positive",
                                          icon: "sent-to-case-management",
                                          title: "",
                                          info: ""}],
               [forPrinting,             {css: "lupicon-circle-section-sign positive",
                                          icon: "for-printing",
                                          title: "",
                                          info: ""}],
               [notPublic,               {css: "lupicon-lock primary",
                                          icon: "not-public",
                                          title: "",
                                          info: ""}],
               [archived,                {css: "lupicon-archives positive",
                                          icon: "archived",
                                          title: "",
                                          info: loc("arkistoitu")}]])
      .filter(function(icon) { return _.first(icon)(attachment); })
      .map(_.last)
      .value();
  };

};
