LUPAPISTE.SutiDisplayModel = function() {
  "use strict";
  var self = this;

  ko.utils.extend( self, new LUPAPISTE.ComponentBaseModel());

  var service = lupapisteApp.services.sutiService;

  self.showSuti = service.sutiEnabled;
  self.open = ko.observable( true );
  self.suti = service.sutiDetails;
  self.waiting = ko.observable();
  self.sutiTitle = ko.observable();

  function sutiComputed( key ) {
    return self.disposedComputed( {
      read: function() {
        return _.get( self.suti(), ["suti", key]);
      },
      write: function( value ) {
        var app = lupapisteApp.models.application;
        if( app.id() ) {
          service.updateApplication( app, key, value, self.waiting);
        }
      }
    });
  }

  self.sutiId = sutiComputed( "id");

  self.sutiAdded = sutiComputed( "added");

  self.disposedComputed( function() {
    var app = lupapisteApp.models.application;
    if( app.id() && app.state()) {
      self.sutiTitle( loc( "suti.display-title",
                           util.prop.toHumanFormat( app.propertyId())));
      service.fetchApplicationData( app, self.waiting );
    }
  });

  self.sutiLink = function() {
    window.open( self.suti().www, "_blank");
  };

  self.note = self.disposedComputed( function() {
    var prods = self.suti().products;
    var msg = false;
    if( self.sutiAdded() ) {
      msg = "suti.display-added-note";
    } else {
      if( _.isString( prods ) ) {
        msg = prods;
      }
      if( _.trim( self.sutiId()) && _.isEmpty( prods )) {
        msg = "suti.display-empty";
      }
    }
    return msg && {text: msg, error: /error/.test( msg )};
  });


  self.products = self.disposedComputed( function() {
    return self.note() ? [] : self.suti().products;
  });

  self.enabled = self.disposedPureComputed( function() {
    var user = features.enabled("suti") && lupapisteApp.models.applicationAuthModel.ok( "suti-update-id");
    var idEnabled = user && !self.sutiAdded();
    return { id: idEnabled,
             link: idEnabled && self.suti().www,
             added: user
           };
  });
};
