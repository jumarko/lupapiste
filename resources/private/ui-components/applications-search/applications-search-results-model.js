LUPAPISTE.ApplicationsSearchResultsModel = function(params) {
  "use strict";

  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.dataProvider = params.dataProvider;
  self.data = ko.pureComputed(function() {
    return _.map(self.dataProvider.results(), function(item) {
      var verdict = util.getIn(item, ["verdicts"]).length;
      console.log(verdict);
      item.kuntalupatunnus = util.getIn(item, ["verdicts", verdict, "kuntalupatunnus"]);
      item.verdictDate = util.getIn(item, ["verdicts", verdict, "paatokset", 0, "paivamaarat", "anto"]);
      item.searchType = self.dataProvider.searchResultType();
      if (item.foremanRole) {
        item.foremanRoleI18nkey = "osapuoli.tyonjohtaja.kuntaRoolikoodi." + item.foremanRole;
      }
      console.log(item);
      return item;
    });
  });

  self.gotResults = params.gotResults;

  self.selectedTab = self.dataProvider.searchResultType;

  self.openApplication = function(model, event, target) {
    pageutil.openApplicationPage(model, target);
  };

  self.dispose = _.partial(hub.unsubscribe, self.onPageLoad);

  self.disposedComputed(function () {
    ko.mapping.toJS(self.dataProvider.sort);
    self.dataProvider.skip(0);
  });

  self.columns = ko.observableArray([
    util.createSortableColumn("first",   "applications.indicators", {colspan: lupapisteApp.models.currentUser.isAuthority() ? "5" : "4",
                                                                     sortable: false,
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("second",  "applications.type",       {sortField: "type",
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("third",   "applications.location",   {sortField: "location",
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("fourth",  "applications.operation",  {sortable: false,
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("fifth",   "applications.applicant",  {sortField: "applicant",
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("sixth",   "applications.submitted",  {sortField: "submitted",
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("seventh", "applications.updated",    {sortField: "modified",
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("eight",   "applications.status",     {sortField: "state",
                                                                     currentSort: self.dataProvider.sort}),
    util.createSortableColumn("ninth",   "application.handlers",  {sortField: "handler",
                                                                   currentSort: self.dataProvider.sort})
  ]);

  ko.computed(function() {
    if (self.selectedTab() === "construction") {
      self.columns.splice(5, 1, util.createSortableColumn("sixth",   "applications.verdictGiven",  {sortField: "submitted", currentSort: self.dataProvider.sort}));
    } else {
      self.columns.splice(5, 1, util.createSortableColumn("sixth",   "applications.submitted",  {sortField: "submitted", currentSort: self.dataProvider.sort}));
    }
  });

  // Scroll support.
  hub.send( "scrollService::setName", {name: "search-results"});
  hub.send( "scrollService::follow", {hashRe: /\/applications$/} );


  // Scroll position support
  self.scrollPop = _.debounce( function()  {
    _.defer( hub.send,  "scrollService::pop", {name: "search-results"});
  }, 100 );

};