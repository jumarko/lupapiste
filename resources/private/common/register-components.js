jQuery(document).ready(function() {
  "use strict";

  var components = [
    {name: "modal-dialog"},
    {name: "message-panel"},
    {name: "fill-info", synchronous: true},
    {name: "foreman-history", synchronous: true},
    {name: "foreman-other-applications", synchronous: true},
    {name: "docgen-group", synchronous: true},
    {name: "docgen-repeating-group", synchronous: true},
    {name: "docgen-table", synchronous: true},
    {name: "docgen-checkbox", model: "docgen-input-model", synchronous: true},
    {name: "docgen-select", synchronous: true},
    {name: "docgen-string", model: "docgen-input-model", synchronous: true},
    {name: "docgen-localized-string", model: "docgen-input-model", synchronous: true},
    {name: "docgen-inline-string", model: "docgen-input-model", synchronous: true},
    {name: "docgen-button", synchronous: true},
    {name: "docgen-date", synchronous: true},
    {name: "construction-waste-report", synchronous: true},
    {name: "attachments-multiselect"},
    {name: "authority-select"},
    {name: "authority-select-dialog"},
    {name: "base-autocomplete", model: "autocomplete-base-model"},
    {name: "autocomplete"},
    {name: "export-attachments"},
    {name: "neighbors-owners-dialog"},
    {name: "neighbors-edit-dialog"},
    {name: "company-selector"},
    {name: "company-invite"},
    {name: "company-invite-dialog"},
    {name: "submit-button-group"},
    {name: "yes-no-dialog"},
    {name: "yes-no-button-group"},
    {name: "company-registration-init"},
    {name: "invoice-operator-selector"},
    {name: "ok-dialog"},
    {name: "ok-button-group"},
    {name: "company-edit"},
    {name: "tags-editor"},
    {name: "municipality-maps"},
    {name: "municipality-maps-server"},
    {name: "municipality-maps-layers"},
    {name: "municipality-maps-map"},
    {name: "upload"},
    {name: "openlayers-map"},
    {name: "vetuma-init"},
    {name: "vetuma-status"},
    {name: "help-toggle"},
    {name: "address"},
    {name: "applications-search"},
    {name: "applications-search-tabs"},
    {name: "applications-search-results"},
    {name: "applications-search-filter"},
    {name: "applications-search-filters-list"},
    {name: "applications-search-paging"},
    {name: "applications-foreman-search-filter", model: "applications-search-filter-model"},
    {name: "applications-foreman-search-tabs", template: "applications-search-tabs-template"},
    {name: "applications-foreman-search-filters-list", template: "applications-search-filters-list-template"},
    {name: "applications-foreman-search-results"},
    {name: "autocomplete-tags", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-operations", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-organizations", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-areas", template: "autocomplete-tags-components-template"},
    {name: "autocomplete-handlers"},
    {name: "autocomplete-application-tags", template: "autocomplete-tags-components-template"},
    {name: "add-property"},
    {name: "add-property-dialog"},
    {name: "autocomplete-saved-filters"},
    {name: "indicator"},
    {name: "indicator-icon"},
    {name: "accordion"},
    {name: "date-field", model: "input-field-model"},
    {name: "text-field", model: "input-field-model"},
    {name: "textarea-field", model: "input-field-model"},
    {name: "checkbox-field", model: "input-field-model"},
    {name: "select-field"},
    {name: "radio-field"},
    {name: "search-field"},
    {name: "maaraala-tunnus", synchronous: true},
    {name: "property-group", synchronous: true},
    {name: "password-field"},
    {name: "accordion-toolbar", synchronous: true},
    {name: "group-approval", synchronous: true},
    {name: "submit-button"},
    {name: "remove-button"},
    {name: "publish-application"},
    {name: "move-to-proclaimed"},
    {name: "move-to-verdict-given"},
    {name: "move-to-final"},
    {name: "bulletin-versions"},
    {name: "bulletin-tab"},
    {name: "bulletin-comments"},
    {name: "infinite-scroll"},
    {name: "statements-tab"},
    {name: "statements-table"},
    {name: "statement-edit"},
    {name: "statement-edit-reply"},
    {name: "statement-reply-request"},
    {name: "statement-control-buttons"},
    {name: "statement-attachments"},
    {name: "guest-authorities"},
    {name: "bubble-dialog"},
    {name: "application-guests"},
    {name: "side-panel"},
    {name: "conversation"},
    {name: "authority-notice"},
    {name: "authorized-parties"}
];

  _.forEach(components, function(component) {
    ko.components.register(component.name, {
      viewModel: LUPAPISTE[_.capitalize(_.camelCase(component.model ? component.model : component.name + "Model"))],
      template: { element: (component.template ? component.template : component.name + "-template")},
      synchronous: component.synchronous ? true : false
    });
  });
});
