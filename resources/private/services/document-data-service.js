LUPAPISTE.DocumentDataService = function() {
  "use strict";

  var self = this;
  self.applicationId = null;
  self.propertyId = null;
  self.model = ko.observableArray();

  // Application

  self.setApplication = function(application) {
    self.applicationId = application.id;
    self.propertyId = application.propertyId;
  }

  // Document

  self.findDocumentById = function(id) {
    return _.find(self.model(), function(doc) {
      return doc.id === id;
    });    
  }

  self.addDocument = function(doc, options) {
    if (!self.applicationId) {
      self.setApplication(lupapisteApp.models.application)
    }
    if (self.findDocumentById(doc.id)) {
      return -1;
    } else {
      return self.model.push( _.extend({
          id: doc.id,
          path: [],
          name: doc.schema.info.name,
          schema: doc.schema,
          isDisabled: options && options.disabled
        },
        createDataModel(_.extend({type: "document"}, doc.schema.info, doc.schema), doc.data, [])
      ));
    }
  }

  self.getInDocument = function(documentId, path) {
    var doc = self.findDocumentById(documentId);
    return doc && getIn(doc, path);
  }

  self.removeDocument = function(id) {
    return self.model.remove(self.findDocumentById(id));
  }

  self.addRepeatingGroup = function(documentId, path) {
    var repeatingModel = self.getInDocument(documentId, path);
    return pushToRepeating(repeatingModel, {});
  }

  self.copyRepeatingGroup = function(documentId, path, index, indicator) {
    var repeatingModel = self.getInDocument(documentId, path);
    var rawModel = getAsRaw(findByIndex(repeatingModel, index));
    var repLength = pushToRepeating(repeatingModel, rawModel);
    var updates = getAsUpdates(repeatingModel.model()[repLength - 1]);
    self.updateDoc(documentId, 
                   updates,
                   indicator);
  }

  self.removeRepeatingGroup = function(documentId, path, index, indicator) {
    var repeatingModel = self.getInDocument(documentId, path);
    var cb = function() {
      removeByIndex(repeatingModel, index);
    };
    var params = {
      path: path.concat(index)
    };
    command("remove-document-data", documentId, params, {
      indicator: indicator,
      cb: cb
    });
  }

  self.updateDoc = function(documentId, updates, indicator, results, cb) {
    var params = {
      updates: _.map(updates, function(update) {
        return [update[0].join("."), update[1]];
      })
    };
    command("update-doc", documentId, params, {
      indicator: indicator,
      results: results,
      cb: cb
    });
  }

  function command(commandName, documentId, params, opts) {
    var results = opts.results || {};
    var indicator = opts.indicator || _.noop;
    var cb = opts.cb || _.noop;
    ajax
      .command(commandName, _.extend({
          doc: documentId,
          id: self.applicationId(),
          collection: "documents"
        }, 
        params)
      )
      .success(function(e) {
        _.forEach(results, function(result) {
          // TODO: hub.send(result)
          var path = result[0], resultObs = result[1];
          var res = _.find(e.results, function(res) {
            return _.isEqual(res.path, path);
          });
          resultObs(res && res.result);
        });
      
        indicator({type: "saved"});
        cb(e);
      })
      .error(function () {
        indicator({type: "err"});
      })
      .fail(function () {
        indicator({type: "err"});
      })
      .call();
  };

  //
  // Repeating utilities
  //

  function createRepeatingUnitDataModel(schema, rawModel, path, index) {
    return _.extend(
        {index: index},
        isGroupType(schema) ? 
          createGroupDataModel(schema, rawModel, path.concat(index)) : 
          createInputDataModel(schema, rawModel, path.concat(index))
    );
  }

  function createRepeatingDataModel(schema, rawModel, path) {
    return {
      path: path,
      schema: schema,
      model: ko.observableArray(_.map(rawModel, function(subModel, index) {
        return createRepeatingUnitDataModel(schema, subModel, path, index);
      }))
    };
  }

  function findByIndex(repeatingModel, index) {
    return _.find(repeatingModel.model ? repeatingModel.model() : repeatingModel(), function(rep) {
      return _.parseInt(rep.index) === _.parseInt(index);
    });
  }

  function removeByIndex(repeatingModel, index) {
    return repeatingModel.model.remove(findByIndex(repeatingModel, index));
  }

  function pushToRepeating(repeatingModel, rawModel) {
    var ind = _.parseInt( _(repeatingModel.model()).map("index").max() ) + 1 || 0;
    var path = repeatingModel.path;
    return repeatingModel.model.push(createRepeatingUnitDataModel(repeatingModel.schema, rawModel, path, ind));
  }

  //
  // Group utilities
  //

  function createGroupDataModel(schema, rawModel, path) {
    return {
      path: path,
      schema: schema,
      model: _(schema.body).map(function(subSchema) {
        return [subSchema.name, createDataModel(subSchema, rawModel[subSchema.name], path.concat(subSchema.name))];
      }).zipObject().value()
    };
  }

  //
  // Input utilities
  //

  function createInputDataModel(schema, rawModel, path) {
    return {path: path,
            schema: schema,
            model: ko.observable(rawModel && rawModel.value)};
  }

  //
  // General utilities
  //

  function isRepeating(schema) {
    return schema.repeating || schema.type === "table";
  }

  function isGroupType(schema) {
    return _.contains(["group", "table", "location", "document"], schema.type);
  }

  function createDataModel(schema, rawModel, path) {
    if (isRepeating(schema)) {
      return createRepeatingDataModel(schema, rawModel, path);
    } else if (isGroupType(schema)) {
      return createGroupDataModel(schema, rawModel, path);
    } else {
      return createInputDataModel(schema, rawModel, path);
    }
  }

  function get(model, key) {
    if (_.has(model, "model")) {
      return get(model.model, key);
    } else if (ko.isObservable(model) && _.isArray(model())) {
      return findByIndex(model, key);
    } else if (ko.isObservable(model)) {
      return model() && model()[key];
    } else if (_.isObject(model)) {
      return model[key];
    }
  }

  function getIn(model, path) {
    return _.reduce(path, get, model);
  }

  function getAsRaw(model) {
    var recur = function(dataModel) {
      if (_.has(dataModel, "model")) {
        return recur(dataModel.model);

      } else if (_.isArray(dataModel)) {
        return _(dataModel)
          .map(function(rep) { return [rep.index, rep.model]; })
          .zipObject()
          .mapValues(recur)
          .value();

      } else if (_.isObject(dataModel)) {
        return _.mapValues(dataModel, recur);

      } else {
        return {value: dataModel};
      }
    }
    return recur(ko.toJS(model));
  }

  function getAsUpdates(dataModel) {
    var recur = function(dataModel) {
      if (_.isObject(dataModel.model)) {
        return _(dataModel.model)
          .map(recur)
          .flatten()
          .filter(1)
          .value();
          
      } else {
        return [[dataModel.path, dataModel.model]];
      }
    }
    return recur(ko.toJS(dataModel));
  }
}