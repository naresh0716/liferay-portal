/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.vulcan.jaxrs.writer.json.internal.writer;

import static org.osgi.service.component.annotations.ReferenceCardinality.AT_LEAST_ONE;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.vulcan.binary.BinaryFunction;
import com.liferay.vulcan.error.VulcanDeveloperError;
import com.liferay.vulcan.error.VulcanDeveloperError.MustHaveProvider;
import com.liferay.vulcan.list.FunctionalList;
import com.liferay.vulcan.message.json.JSONObjectBuilder;
import com.liferay.vulcan.message.json.SingleModelMessageMapper;
import com.liferay.vulcan.pagination.SingleModel;
import com.liferay.vulcan.response.control.Embedded;
import com.liferay.vulcan.response.control.Fields;
import com.liferay.vulcan.result.Try;
import com.liferay.vulcan.wiring.osgi.manager.ProviderManager;
import com.liferay.vulcan.wiring.osgi.manager.ResourceManager;
import com.liferay.vulcan.wiring.osgi.model.RelatedCollection;
import com.liferay.vulcan.wiring.osgi.model.RelatedModel;
import com.liferay.vulcan.wiring.osgi.util.GenericUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Gives Vulcan the ability to write single models. For that end it uses the
 * right {@link SingleModelMessageMapper} in accordance with the media type.
 *
 * @author Alejandro Hernández
 * @author Carlos Sierra Andrés
 * @author Jorge Ferrer
 */
@Component(
	immediate = true, property = "liferay.vulcan.message.body.writer=true"
)
@Provider
public class SingleModelMessageBodyWriter<T>
	implements MessageBodyWriter<Try.Success<SingleModel<T>>> {

	@Override
	public long getSize(
		Try.Success<SingleModel<T>> success, Class<?> clazz, Type genericType,
		Annotation[] annotations, MediaType mediaType) {

		return -1;
	}

	@Override
	public boolean isWriteable(
		Class<?> clazz, Type genericType, Annotation[] annotations,
		MediaType mediaType) {

		Try<Class<Object>> classTry = GenericUtil.getGenericClassTry(
			genericType, Try.class);

		return classTry.filter(
			SingleModel.class::equals
		).isSuccess();
	}

	@Override
	public void writeTo(
			Try.Success<SingleModel<T>> success, Class<?> clazz,
			Type genericType, Annotation[] annotations, MediaType mediaType,
			MultivaluedMap<String, Object> httpHeaders,
			OutputStream entityStream)
		throws IOException, WebApplicationException {

		PrintWriter printWriter = new PrintWriter(entityStream, true);

		Stream<SingleModelMessageMapper<T>> stream =
			_singleModelMessageMappers.stream();

		String mediaTypeString = mediaType.toString();

		SingleModel<T> singleModel = success.getValue();

		T model = singleModel.getModel();

		Class<T> modelClass = singleModel.getModelClass();

		SingleModelMessageMapper<T> singleModelMessageMapper = stream.filter(
			messageMapper ->
				mediaTypeString.equals(messageMapper.getMediaType()) &&
				messageMapper.supports(model, modelClass, _httpHeaders)
		).findFirst(
		).orElseThrow(
			() -> new VulcanDeveloperError.MustHaveMessageMapper(
				mediaTypeString, modelClass)
		);

		JSONObjectBuilder jsonObjectBuilder = new JSONObjectBuilderImpl();

		Optional<Fields> fieldsOptional = _providerManager.provide(
			Fields.class, _httpServletRequest);

		Fields fields = fieldsOptional.orElseThrow(
			() -> new MustHaveProvider(Fields.class));

		Optional<Embedded> embeddedOptional = _providerManager.provide(
			Embedded.class, _httpServletRequest);

		Embedded embedded = embeddedOptional.orElseThrow(
			() -> new MustHaveProvider(Embedded.class));

		_writeModel(
			singleModelMessageMapper, jsonObjectBuilder, singleModel, fields,
			embedded);

		JSONObject jsonObject = jsonObjectBuilder.build();

		printWriter.println(jsonObject.toString());

		printWriter.close();
	}

	private <U, V> void _writeEmbeddedRelatedModel(
		SingleModelMessageMapper<?> singleModelMessageMapper,
		JSONObjectBuilder jsonObjectBuilder, RelatedModel<U, V> relatedModel,
		SingleModel<U> parentSingleModel,
		FunctionalList<String> parentEmbeddedPathElements, Fields fields,
		Embedded embedded) {

		_writerHelper.writeRelatedModel(
			relatedModel, parentSingleModel, parentEmbeddedPathElements,
			_httpServletRequest, fields, embedded,
			(singleModel, embeddedPathElements) -> {
				Class<V> modelClass = singleModel.getModelClass();

				_writerHelper.writeFields(
					singleModel.getModel(), modelClass, fields,
					(fieldName, value) ->
						singleModelMessageMapper.mapEmbeddedResourceField(
							jsonObjectBuilder, embeddedPathElements, fieldName,
							value));

				_writerHelper.writeLinks(
					modelClass, fields,
					(fieldName, link) ->
						singleModelMessageMapper.mapEmbeddedResourceLink(
							jsonObjectBuilder, embeddedPathElements, fieldName,
							link));

				Map<String, BinaryFunction<V>> binaryFunctions =
					_resourceManager.getBinaryFunctions(modelClass);

				_writerHelper.writeBinaries(
					binaryFunctions, singleModel, _httpServletRequest,
					(fieldName, value) ->
						singleModelMessageMapper.mapEmbeddedResourceField(
							jsonObjectBuilder, embeddedPathElements, fieldName,
							value));

				_writerHelper.writeTypes(
					modelClass,
					types -> singleModelMessageMapper.mapEmbeddedResourceTypes(
						jsonObjectBuilder, embeddedPathElements, types));

				List<RelatedModel<V, ?>> embeddedRelatedModels =
					_resourceManager.getEmbeddedRelatedModels(modelClass);

				embeddedRelatedModels.forEach(
					embeddedRelatedModel -> _writeEmbeddedRelatedModel(
						singleModelMessageMapper, jsonObjectBuilder,
						embeddedRelatedModel, singleModel, embeddedPathElements,
						fields, embedded));

				List<RelatedModel<V, ?>> linkedRelatedModels =
					_resourceManager.getLinkedRelatedModels(modelClass);

				linkedRelatedModels.forEach(
					linkedRelatedModel -> _writeLinkedRelatedModel(
						singleModelMessageMapper, jsonObjectBuilder,
						linkedRelatedModel, singleModel, embeddedPathElements,
						fields, embedded));

				List<RelatedCollection<V, ?>> relatedCollections =
					_resourceManager.getRelatedCollections(modelClass);

				relatedCollections.forEach(
					relatedCollection -> _writeRelatedCollection(
						singleModelMessageMapper, jsonObjectBuilder,
						relatedCollection, singleModel, embeddedPathElements,
						fields));
			},
			(url, embeddedPathElements, isEmbedded) -> {
				if (isEmbedded) {
					singleModelMessageMapper.mapEmbeddedResourceURL(
						jsonObjectBuilder, embeddedPathElements, url);
				}
				else {
					singleModelMessageMapper.mapLinkedResourceURL(
						jsonObjectBuilder, embeddedPathElements, url);
				}
			});
	}

	private <U, V> void _writeLinkedRelatedModel(
		SingleModelMessageMapper<?> singleModelMessageMapper,
		JSONObjectBuilder jsonObjectBuilder, RelatedModel<U, V> relatedModel,
		SingleModel<U> parentSingleModel,
		FunctionalList<String> parentEmbeddedPathElements, Fields fields,
		Embedded embedded) {

		_writerHelper.writeLinkedRelatedModel(
			relatedModel, parentSingleModel, parentEmbeddedPathElements,
			_httpServletRequest, fields, embedded,
			(url, embeddedPathElements) ->
				singleModelMessageMapper.mapLinkedResourceURL(
					jsonObjectBuilder, embeddedPathElements, url));
	}

	private <U> void _writeModel(
		SingleModelMessageMapper<U> singleModelMessageMapper,
		JSONObjectBuilder jsonObjectBuilder, SingleModel<U> singleModel,
		Fields fields, Embedded embedded) {

		U model = singleModel.getModel();

		Class<U> modelClass = singleModel.getModelClass();

		singleModelMessageMapper.onStart(
			jsonObjectBuilder, model, modelClass, _httpHeaders);

		_writerHelper.writeFields(
			singleModel.getModel(), singleModel.getModelClass(), fields,
			(field, value) -> singleModelMessageMapper.mapField(
				jsonObjectBuilder, field, value));

		_writerHelper.writeLinks(
			modelClass, fields,
			(fieldName, link) -> singleModelMessageMapper.mapLink(
				jsonObjectBuilder, fieldName, link));

		Map<String, BinaryFunction<U>> binaryFunctions =
			_resourceManager.getBinaryFunctions(modelClass);

		_writerHelper.writeBinaries(
			binaryFunctions, singleModel, _httpServletRequest,
			(field, value) -> singleModelMessageMapper.mapField(
				jsonObjectBuilder, field, value));

		_writerHelper.writeTypes(
			modelClass,
			types -> singleModelMessageMapper.mapTypes(
				jsonObjectBuilder, types));

		String url = _writerHelper.getSingleURL(
			singleModel, _httpServletRequest);

		singleModelMessageMapper.mapSelfURL(jsonObjectBuilder, url);

		List<RelatedModel<U, ?>> embeddedRelatedModels =
			_resourceManager.getEmbeddedRelatedModels(modelClass);

		embeddedRelatedModels.forEach(
			embeddedRelatedModel -> _writeEmbeddedRelatedModel(
				singleModelMessageMapper, jsonObjectBuilder,
				embeddedRelatedModel, singleModel, null, fields, embedded));

		List<RelatedModel<U, ?>> linkedRelatedModels =
			_resourceManager.getLinkedRelatedModels(modelClass);

		linkedRelatedModels.forEach(
			linkedRelatedModel -> _writeLinkedRelatedModel(
				singleModelMessageMapper, jsonObjectBuilder, linkedRelatedModel,
				singleModel, null, fields, embedded));

		List<RelatedCollection<U, ?>> relatedCollections =
			_resourceManager.getRelatedCollections(modelClass);

		relatedCollections.forEach(
			relatedCollection -> _writeRelatedCollection(
				singleModelMessageMapper, jsonObjectBuilder, relatedCollection,
				singleModel, null, fields));

		singleModelMessageMapper.onFinish(
			jsonObjectBuilder, model, modelClass, _httpHeaders);
	}

	private <U, V> void _writeRelatedCollection(
		SingleModelMessageMapper<?> singleModelMessageMapper,
		JSONObjectBuilder jsonObjectBuilder,
		RelatedCollection<U, V> relatedCollection,
		SingleModel<U> parentSingleModel,
		FunctionalList<String> parentEmbeddedPathElements, Fields fields) {

		_writerHelper.writeRelatedCollection(
			relatedCollection, parentSingleModel, parentEmbeddedPathElements,
			_httpServletRequest, fields,
			(url, embeddedPathElements) ->
				singleModelMessageMapper.mapLinkedResourceURL(
					jsonObjectBuilder, embeddedPathElements, url));
	}

	@Context
	private HttpHeaders _httpHeaders;

	@Context
	private HttpServletRequest _httpServletRequest;

	@Reference
	private ProviderManager _providerManager;

	@Reference
	private ResourceManager _resourceManager;

	@Reference(cardinality = AT_LEAST_ONE, policyOption = GREEDY)
	private List<SingleModelMessageMapper<T>> _singleModelMessageMappers;

	@Reference
	private WriterHelper _writerHelper;

}