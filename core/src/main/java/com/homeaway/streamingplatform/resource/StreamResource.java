/* Copyright (c) 2018 Expedia Group.
 * All rights reserved.  http://www.homeaway.com

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *      http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.homeaway.streamingplatform.resource;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import lombok.extern.slf4j.Slf4j;

import com.codahale.metrics.annotation.Timed;

import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import com.homeaway.streamingplatform.db.dao.StreamClientDao;
import com.homeaway.streamingplatform.db.dao.StreamDao;
import com.homeaway.streamingplatform.exceptions.InvalidStreamException;
import com.homeaway.streamingplatform.exceptions.StreamNotFoundException;
import com.homeaway.streamingplatform.model.Consumer;
import com.homeaway.streamingplatform.model.Producer;
import com.homeaway.streamingplatform.model.Stream;
import com.homeaway.streamingplatform.utils.ResourceUtils;
import com.homeaway.streamingplatform.utils.StreamRegistryUtils;
import com.homeaway.streamingplatform.utils.StreamRegistryUtils.EntriesPage;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Api(value = "Stream-registry API", description = "Stream Registry API, a centralized governance tool for managing streams.")
@Path("/v0/streams")
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class StreamResource {

    private static final int DEFAULT_STREAMS_PAGE_NUMBER = 0;

    private static final int DEFAULT_STREAMS_PAGE_SIZE = 10;

    private final StreamDao streamDao;
    private final StreamClientDao<Producer> producerDao;
    private final StreamClientDao<Consumer> consumerDao;

    public StreamResource(StreamDao streamDao, StreamClientDao<Producer> producerDao, StreamClientDao<Consumer> consumerDao) {
        this.streamDao = streamDao;
        this.producerDao = producerDao;
        this.consumerDao = consumerDao;
    }

    @PUT
    @ApiOperation(
        value = "Upsert stream",
        notes = "Create/Update a stream and its meta-data",
        tags = "streams")
    @ApiResponses(value = { @ApiResponse(code = 202, message = "Request accepted"),
        @ApiResponse(code = 400, message = "Validation Exception while creating a stream"),
        @ApiResponse(code = 500, message = "Error Occurred while getting data") })
    @Path("/{streamName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed
    public Response upsertStream(@ApiParam(value = "stream entity", required = true)
        Stream stream) {
        try {
            streamDao.upsertStream(stream);
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (BadRequestException | InvalidStreamException | IllegalArgumentException se) {
            log.error("Error creating stream={}", stream.getName(), se);
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), se.getMessage()))
                .build();
        } catch(Exception e) {
            log.error("Error creating stream={}", stream.getName(), e);
            throw new InternalServerErrorException(String.format("Error creating stream=%s ; Error=%s", stream.getName(), e.getMessage()), e);
        }
    }

    @GET
    @Path("/{streamName}")
    @ApiOperation(
        value = "Get stream",
        notes = "Returns a single stream resource",
        tags = "streams",
        response = Stream.class)
    @ApiResponses(value = { @ApiResponse(code = 200, message = "successful operation", response = Stream.class),
        @ApiResponse(code = 500, message = "Error Occurred while getting data"),
        @ApiResponse(code = 404, message = "Stream not found") })
    @Produces(MediaType.APPLICATION_JSON)
    @Timed
    public Response getStream(@ApiParam(value = "Stream name", required = true)
    @PathParam("streamName") String streamName) {
        Optional<Stream> stream = streamDao.getStream(streamName);
        try {
            if (!stream.isPresent()) {
                return ResourceUtils.streamNotFound(streamName);
            }
            return Response.ok().entity(stream.get()).build();
        } catch (Exception e) {
            log.error("Error occurred while getting data from Stream Registry.", e);
            throw new InternalServerErrorException("Error occurred while getting data from Stream Registry", e);
        }
    }

    @GET
    @ApiOperation(
        value = "Get all streams",
        notes = "Get all streams from stream registry",
        tags = "streams",
        response = EntriesPage.class)
    @ApiResponses(value = {@ApiResponse(code = 200, message = "Stream(s) read successfully", response = EntriesPage.class),
        @ApiResponse(code = 500, message = "Error Occurred while getting data"),
        @ApiResponse(code = 404, message = "Stream not found") })
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Timed
    public Response getAllStreams(
        @ApiParam(
            value = "Page number (zero based), default: 0",
            defaultValue = "" + DEFAULT_STREAMS_PAGE_NUMBER)
        @QueryParam("pageNumber") Optional<Integer> pageNumber,
        @ApiParam(
            value = "Page size",
            defaultValue = "" + DEFAULT_STREAMS_PAGE_SIZE)
        @QueryParam("pageSize") Optional<Integer> pageSize) {
        try {
            final int pSize = pageSize.orElse(DEFAULT_STREAMS_PAGE_SIZE);
            final int pNumber = pageNumber.orElse(DEFAULT_STREAMS_PAGE_NUMBER);
            final List<Stream> allStreams = streamDao.getAllStreams();
            final int totalSize = allStreams.size();

            List<Stream> streamsPage = Optional.ofNullable(StreamRegistryUtils
                .paginate(
                    allStreams,
                    pSize)
                .get(pNumber))
                .orElse(Collections.emptyList());

            return Response.ok()
                    .entity(StreamRegistryUtils.toEntriesPage(streamsPage, totalSize, pSize, pNumber))
                    .build();
        } catch (Exception e) {
            log.error("Error occurred while getting data from Stream Registry.", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DELETE
    @ApiOperation(value = "Delete stream with path param stream name", tags = "streams")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "Stream deleted successfully"),
            @ApiResponse(code = 404, message = "Stream not found"),
            @ApiResponse(code = 500, message = "Error Occurred while getting data")})
    @Path("/{streamName}")
    @Timed
    public Response deleteStream(@ApiParam(value = "Stream object that needs to be deleted from the Stream Registry", required = true)
    @PathParam("streamName") String streamName) {
        try {
            streamDao.deleteStream(streamName);
        } catch (StreamNotFoundException e) {
            return ResourceUtils.streamNotFound(streamName);
        } catch (Exception e) {
            log.error("Error occurred while getting data from Stream Registry.", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        return Response.ok().entity("").build();
    }


    @Path("/{streamName}/producers")
    public ProducerResource getProducerResource(){
        return new ProducerResource(streamDao, producerDao);
    }

    @Path("/{streamName}/consumers")
    public ConsumerResource getConsumerResource(){
        return new ConsumerResource(streamDao, consumerDao);
    }

}