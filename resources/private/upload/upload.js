var LUPAPISTE = LUPAPISTE || {};
LUPAPISTE.Upload = {
  fileExtensions: LUPAPISTE.config.fileExtensions.join(", "),
  applicationId: ko.observable(),
  attachmentId: ko.observable(),
  attachmentType: ko.observable(),
  attachmentTypeGroups: ko.observableArray(),
  typeSelector: ko.observable(false),
  operationId: ko.observable(),
  opSelector: ko.observable(false),
  errorMessage: ko.observable(),
  targetType: ko.observable(),
  targetId: ko.observable(),
  locked: ko.observable(),
  selectableGroups: ko.observableArray(),
  helpVisible: ko.observable(false),
  archiveEnabled: ko.observable(false)
};

LUPAPISTE.Upload.setModel = function(options) {
  "use strict";
  LUPAPISTE.Upload.applicationId(options.applicationId);
  LUPAPISTE.Upload.attachmentId(options.attachmentId);
  LUPAPISTE.Upload.attachmentType(options.attachmentType);
  LUPAPISTE.Upload.typeSelector(options.typeSelector ? true : false);
  LUPAPISTE.Upload.selectableGroups([{id: options.operationId || options.group}]);
  LUPAPISTE.Upload.operationId(options.operationId || options.group);
  LUPAPISTE.Upload.opSelector(options.opSelector ? true : false);
  LUPAPISTE.Upload.errorMessage(options.errorMessage);
  LUPAPISTE.Upload.targetType(options.target ? options.target.type : null);
  LUPAPISTE.Upload.targetId(options.target ? options.target.id : null);
  LUPAPISTE.Upload.locked(options.locked || false);
  LUPAPISTE.Upload.archiveEnabled(options.archiveEnabled || false);
};

LUPAPISTE.Upload.loadTypes = function(applicationId) {
  "use strict";
  if (applicationId) {
    ajax
      .query("attachment-types", {id: applicationId})
      .success(function(d) {
        LUPAPISTE.Upload.attachmentTypeGroups(
          _(d.attachmentTypes)
            .groupBy("type-group")
            .map(function(types, group) { return  { group: group, types: types }; })
            .value());
        var uploadForm$ = $("#attachmentUploadForm");
        uploadForm$.applyBindings(LUPAPISTE.Upload);
        $("#initLoader").hide();
        uploadForm$.show();
      })
      .call();
  }
};

LUPAPISTE.Upload.loadGroups = function(applicationId) {
  "use strict";
  if (applicationId) {
    ajax
      .query("attachment-groups", {id: applicationId})
      .success(function(data) {
        if (data.groups) {
          LUPAPISTE.Upload.selectableGroups(_.map(data.groups, function(g) {
            // group type is uploaded as operationId
            if (!g.id) {
              g.id = g.groupType;
            }
            return g;
          }));
          LUPAPISTE.Upload.operationId.valueHasMutated();
        }
      })
      .call();
  }
};

LUPAPISTE.Upload.getGroupOptionsText = function(item) {
  "use strict";
  if (item.groupType === "operation") {
    return item.description ? loc([item.name, "_group_label"]) + " - " + item.description : loc([item.name, "_group_label"]);
  } else if (item.groupType) {
    return loc([item.groupType, "_group_label"]);
  }
};


LUPAPISTE.Upload.init = function(options) {
  "use strict";
  LUPAPISTE.Upload.setModel(options);
  LUPAPISTE.Upload.loadTypes(options.applicationId);
  LUPAPISTE.Upload.loadGroups(options.applicationId);
};

LUPAPISTE.Upload.initFromURLParams = function() {
  "use strict";
  if (location.search) {
    var applicationId = pageutil.getURLParameter("applicationId");
    var options = {
      applicationId: applicationId,
      attachmentId: pageutil.getURLParameter("attachmentId"),
      attachmentType: pageutil.getURLParameter("attachmentType"),
      typeSelector: JSON.parse(pageutil.getURLParameter("typeSelector") || false),
      opSelector: JSON.parse(pageutil.getURLParameter("opSelector") || false),
      operationId: pageutil.getURLParameter("operationId"),
      errorMessage: pageutil.getURLParameter("errorMessage"),
      target: {type: pageutil.getURLParameter("targetType"),
               id: pageutil.getURLParameter("targetId")},
      locked: JSON.parse(pageutil.getURLParameter("locked") || false)
    };
    LUPAPISTE.Upload.init(options);
  }
};

$(LUPAPISTE.Upload.initFromURLParams);
