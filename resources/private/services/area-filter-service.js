LUPAPISTE.AreaFilterService = function(applicationFiltersService) {
  "use strict";
  var self = this;

  var _data = ko.observable();

  self.data = ko.pureComputed(function() {
    return _data();
  });

  self.selected = ko.observableArray([]);

  var defaultFilter = ko.pureComputed(function() {
    var applicationFilters = _.find(applicationFiltersService.savedFilters(), function(f) {
      return f.isDefaultFilter();
    });
    return util.getIn(applicationFilters, ["filter", "areas"]) || [];
  });

  ko.computed(function() {
    self.selected([]);
    ko.utils.arrayPushAll(self.selected,
      _(self.data())
        .map("areas")
        .pluck("features")
        .flatten()
        .filter(function(feature) {
          return _.contains(defaultFilter(), feature.id);
        })
        .map(function(feature) {
          return {id: feature.id, label: util.getFeatureName(feature)};
        })
        .value());
  });

  ajax
    .query("get-organization-areas")
    .error(_.noop)
    .success(function(res) {
      _data(res.areas);
    })
    .call();
};
