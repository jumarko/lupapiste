// Appeals listing and creation support for a verdit.
// See VerdictAppealBubbleModel for the creation/edit bubble.
// Params:
//  id:  Verdict ID.
LUPAPISTE.VerdictAppealModel = function( params ) {
  "use strict";
  var self = this;

  var service = lupapisteApp.services.verdictAppealService;

  ko.utils.extend( self, new LUPAPISTE.ComponentBaseModel());

  var verdictId = params.id;

  // Todo: Fetch appeals from the service.
  self.appeals = ko.observableArray();

  self.newBubbleVisible = ko.observable();

  self.toggleNewBubble = function() {
    self.newBubbleVisible( !self.newBubbleVisible());
  };

  // The model is passed to the VerdictAppealBubbleModel
  // as a model parameter.
  self.bubbleModel = function( appealId ) {
    var emptyModel = {appealType: "", authors: "", date: "", extra: "", files: []};
    var model = _.mapValues( emptyModel,
                             function( v ) {
                               return _.isArray( v )
                                 ? ko.observableArray( v )
                                 : ko.observable( v );
                             });

    var error = ko.observable();
    var waiting = ko.observable();

    function initFun() {
      var data = _.defaults( _.find( self.appeals(), {id: appealId}),
                             emptyModel);
      _.each( data, function(v, k ) {
        model[k]( v );
      } );
      error( "");
      waiting( false );
    }

    function okFun() {
      function cb( msg ) {
        error( msg );
        self.newBubbleVisible( Boolean( msg ));
      }
      var message = {verdictId: verdictId};
      if( appealId ) {
        message.appealId = appealId;
      }
      _.assignWith( message, model, function( old, obs ) {
        var v = obs();
        return _.isArray( v ) ? _.pick( v, ["id"]) : v;
      } );
      // TODO: send message to the service.
      // For now we just close the bubble.
      self.sendEvent( service.serviceName,
                      "upsertAppeal",
                      {message: message,
                       callback: cb} );
    }

    return {
      model: model,
      initFun: initFun,
      okFun: okFun,
      error: error,
      waiting: waiting
    };
  };
};
