/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.common.data.DeviceInfo;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportContext;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.adaptor.JsonConverter;
import org.thingsboard.server.common.transport.auth.SessionInfoCreator;
import org.thingsboard.server.common.transport.auth.ValidateDeviceCredentialsResponse;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.*;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Consumer;


/**
 * @author Andrew Shvayka
 */
@RestController
@ConditionalOnExpression("'${service.type:null}'=='tb-transport' || ('${service.type:null}'=='monolith' && '${transport.api_enabled:true}'=='true' && '${transport.http.enabled}'=='true')")
@RequestMapping("/api/v1")
@Slf4j
public class DeviceApiController {

    @Autowired
    private HttpTransportContext transportContext;

    @Autowired
    private DeviceService deviceService;


    // my method
    UUID toUUID(String id) {
        return UUID.fromString(id);
    }

    DeviceInfo checkDeviceInfoId(DeviceId deviceId) throws ThingsboardException {
        try {
            UUID uuid = toUUID("3ebeefc0-b71c-11eb-9a6b-85a1eab0090c");
            TenantId tenantId = new TenantId(uuid);
            DeviceInfo device = deviceService.findDeviceInfoById(tenantId, deviceId);
            return device;
        } catch (Exception e) {
            return null;
        }
    }

    // my API
    @RequestMapping(value = "/hust/set/info/{deviceId}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public DeviceInfo getDeviceInfo(@PathVariable("deviceId") String strDeviceId, HttpServletRequest request) throws ThingsboardException {
        if(strDeviceId == null || strDeviceId.equals("")){
            return  new DeviceInfo();
        }
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
        DeviceInfo deviceInfo = checkDeviceInfoId(deviceId);
        if(deviceInfo == null){
            return new DeviceInfo();
        }else {
            return deviceInfo;
        }
    }

    // my API
    @RequestMapping(value = "/hust/set/{deviceToken}/temperature", method = RequestMethod.POST, produces = "application/json")
    public ResponseEntity sendTemperature(@PathVariable("deviceToken") String deviceToken,
                                                  @RequestBody String json, HttpServletRequest request){
        String[] strings = json.split(":");
        if(strings.length != 2){
            return ResponseEntity.ok("Bad request");
        }
        String tempVal = strings[1].trim();
        log.info("tempVal2 : {}", tempVal.replace("}","").trim());
        Float temp;
        try{
            temp = Float.parseFloat(tempVal.replace("}","").trim());
        }catch (Exception ex){
            temp = 30F;
        }
        log.info("Temperature : {}", temp);
        if(temp < -40F || temp > 70F){
            return ResponseEntity.ok("Alarm created");
        }
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        Map<String, String> map = new HashMap<>();
        transportContext.getTransportService().myProcess(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    transportService.myProcess(sessionInfo, JsonConverter.convertToTelemetryProto(new JsonParser().parse(json)),
                            new HttpOkCallback(responseWriter), map);
                    reportActivity(sessionInfo);
                })

        );
        return ResponseEntity.ok("Send temperature data successful");
    }


    // thingsboard code
    @RequestMapping(value = "/{deviceToken}/attributes", method = RequestMethod.GET, produces = "application/json")
    public DeferredResult<ResponseEntity> getDeviceAttributes(@PathVariable("deviceToken") String deviceToken,
                                                              @RequestParam(value = "clientKeys", required = false, defaultValue = "") String clientKeys,
                                                              @RequestParam(value = "sharedKeys", required = false, defaultValue = "") String sharedKeys,
                                                              HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    GetAttributeRequestMsg.Builder request = GetAttributeRequestMsg.newBuilder().setRequestId(0);
                    List<String> clientKeySet = !StringUtils.isEmpty(clientKeys) ? Arrays.asList(clientKeys.split(",")) : null;
                    List<String> sharedKeySet = !StringUtils.isEmpty(sharedKeys) ? Arrays.asList(sharedKeys.split(",")) : null;
                    if (clientKeySet != null) {
                        request.addAllClientAttributeNames(clientKeySet);
                    }
                    if (sharedKeySet != null) {
                        request.addAllSharedAttributeNames(sharedKeySet);
                    }
                    TransportService transportService = transportContext.getTransportService();
                    transportService.registerSyncSession(sessionInfo, new HttpSessionListener(responseWriter), transportContext.getDefaultTimeout());
                    transportService.process(sessionInfo, request.build(), new SessionCloseOnErrorCallback(transportService, sessionInfo));
                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/attributes", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postDeviceAttributes(@PathVariable("deviceToken") String deviceToken,
                                                               @RequestBody String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    transportService.process(sessionInfo, JsonConverter.convertToAttributesProto(new JsonParser().parse(json)),
                            new HttpOkCallback(responseWriter));
                    reportActivity(sessionInfo);
                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/telemetry", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postTelemetry(@PathVariable("deviceToken") String deviceToken,
                                                        @RequestBody String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    transportService.process(sessionInfo, JsonConverter.convertToTelemetryProto(new JsonParser().parse(json)),
                            new HttpOkCallback(responseWriter));
                    reportActivity(sessionInfo);
                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/claim", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> claimDevice(@PathVariable("deviceToken") String deviceToken,
                                                      @RequestBody(required = false) String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    DeviceId deviceId = new DeviceId(new UUID(sessionInfo.getDeviceIdMSB(), sessionInfo.getDeviceIdLSB()));
                    transportService.process(sessionInfo, JsonConverter.convertToClaimDeviceProto(deviceId, json),
                            new HttpOkCallback(responseWriter));

                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/rpc", method = RequestMethod.GET, produces = "application/json")
    public DeferredResult<ResponseEntity> subscribeToCommands(@PathVariable("deviceToken") String deviceToken,
                                                              @RequestParam(value = "timeout", required = false, defaultValue = "0") long timeout,
                                                              HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    transportService.registerSyncSession(sessionInfo, new HttpSessionListener(responseWriter),
                            timeout == 0 ? transportContext.getDefaultTimeout() : timeout);
                    transportService.process(sessionInfo, SubscribeToRPCMsg.getDefaultInstance(),
                            new SessionCloseOnErrorCallback(transportService, sessionInfo));

                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/rpc/{requestId}", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> replyToCommand(@PathVariable("deviceToken") String deviceToken,
                                                         @PathVariable("requestId") Integer requestId,
                                                         @RequestBody String json, HttpServletRequest request) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    transportService.process(sessionInfo, ToDeviceRpcResponseMsg.newBuilder().setRequestId(requestId).setPayload(json).build(), new HttpOkCallback(responseWriter));
                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/rpc", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> postRpcRequest(@PathVariable("deviceToken") String deviceToken,
                                                         @RequestBody String json, HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<ResponseEntity>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    JsonObject request = new JsonParser().parse(json).getAsJsonObject();
                    TransportService transportService = transportContext.getTransportService();
                    transportService.registerSyncSession(sessionInfo, new HttpSessionListener(responseWriter), transportContext.getDefaultTimeout());
                    transportService.process(sessionInfo, ToServerRpcRequestMsg.newBuilder().setRequestId(0)
                                    .setMethodName(request.get("method").getAsString())
                                    .setParams(request.get("params").toString()).build(),
                            new SessionCloseOnErrorCallback(transportService, sessionInfo));
                }));
        return responseWriter;
    }

    @RequestMapping(value = "/{deviceToken}/attributes/updates", method = RequestMethod.GET, produces = "application/json")
    public DeferredResult<ResponseEntity> subscribeToAttributes(@PathVariable("deviceToken") String deviceToken,
                                                                @RequestParam(value = "timeout", required = false, defaultValue = "0") long timeout,
                                                                HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(DeviceTransportType.DEFAULT, ValidateDeviceTokenRequestMsg.newBuilder().setToken(deviceToken).build(),
                new DeviceAuthCallback(transportContext, responseWriter, sessionInfo -> {
                    TransportService transportService = transportContext.getTransportService();
                    transportService.registerSyncSession(sessionInfo, new HttpSessionListener(responseWriter),
                            timeout == 0 ? transportContext.getDefaultTimeout() : timeout);
                    transportService.process(sessionInfo, SubscribeToAttributeUpdatesMsg.getDefaultInstance(),
                            new SessionCloseOnErrorCallback(transportService, sessionInfo));

                }));
        return responseWriter;
    }

    @RequestMapping(value = "/provision", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> provisionDevice(@RequestBody String json, HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity> responseWriter = new DeferredResult<>();
        transportContext.getTransportService().process(JsonConverter.convertToProvisionRequestMsg(json),
                new DeviceProvisionCallback(responseWriter));
        return responseWriter;
    }

    private static class DeviceAuthCallback implements TransportServiceCallback<ValidateDeviceCredentialsResponse> {
        private final TransportContext transportContext;
        private final DeferredResult<ResponseEntity> responseWriter;
        private final Consumer<SessionInfoProto> onSuccess;

        DeviceAuthCallback(TransportContext transportContext, DeferredResult<ResponseEntity> responseWriter, Consumer<SessionInfoProto> onSuccess) {
            this.transportContext = transportContext;
            this.responseWriter = responseWriter;
            this.onSuccess = onSuccess;
        }

        @Override
        public void onSuccess(ValidateDeviceCredentialsResponse msg) {
            if (msg.hasDeviceInfo()) {
                onSuccess.accept(SessionInfoCreator.create(msg, transportContext, UUID.randomUUID()));
            } else {
                responseWriter.setResult(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
            }
        }

        @Override
        public void onError(Throwable e) {
            log.warn("Failed to process request", e);
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    private static class DeviceProvisionCallback implements TransportServiceCallback<ProvisionDeviceResponseMsg> {
        private final DeferredResult<ResponseEntity> responseWriter;

        DeviceProvisionCallback(DeferredResult<ResponseEntity> responseWriter) {
            this.responseWriter = responseWriter;
        }

        @Override
        public void onSuccess(ProvisionDeviceResponseMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg).toString(), HttpStatus.OK));
        }

        @Override
        public void onError(Throwable e) {
            log.warn("Failed to process request", e);
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    private static class SessionCloseOnErrorCallback implements TransportServiceCallback<Void> {
        private final TransportService transportService;
        private final SessionInfoProto sessionInfo;

        SessionCloseOnErrorCallback(TransportService transportService, SessionInfoProto sessionInfo) {
            this.transportService = transportService;
            this.sessionInfo = sessionInfo;
        }

        @Override
        public void onSuccess(Void msg) {
        }

        @Override
        public void onError(Throwable e) {
            transportService.deregisterSession(sessionInfo);
        }
    }

    private static class HttpOkCallback implements TransportServiceCallback<Void> {
        private final DeferredResult<ResponseEntity> responseWriter;

        public HttpOkCallback(DeferredResult<ResponseEntity> responseWriter) {
            this.responseWriter = responseWriter;
        }

        @Override
        public void onSuccess(Void msg) {
            responseWriter.setResult(ResponseEntity.ok("Data send successful"));
        }

        @Override
        public void onError(Throwable e) {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    private static class HttpSessionListener implements SessionMsgListener {

        private final DeferredResult<ResponseEntity> responseWriter;

        HttpSessionListener(DeferredResult<ResponseEntity> responseWriter) {
            this.responseWriter = responseWriter;
        }

        @Override
        public void onGetAttributesResponse(GetAttributeResponseMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg).toString(), HttpStatus.OK));
        }

        @Override
        public void onAttributeUpdate(AttributeUpdateNotificationMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg).toString(), HttpStatus.OK));
        }

        @Override
        public void onRemoteSessionCloseCommand(SessionCloseNotificationProto sessionCloseNotification) {
            responseWriter.setResult(new ResponseEntity<>(HttpStatus.REQUEST_TIMEOUT));
        }

        @Override
        public void onToDeviceRpcRequest(ToDeviceRpcRequestMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg, true).toString(), HttpStatus.OK));
        }

        @Override
        public void onToServerRpcResponse(ToServerRpcResponseMsg msg) {
            responseWriter.setResult(new ResponseEntity<>(JsonConverter.toJson(msg).toString(), HttpStatus.OK));
        }
    }

    private void reportActivity(SessionInfoProto sessionInfo) {
        transportContext.getTransportService().process(sessionInfo, TransportProtos.SubscriptionInfoProto.newBuilder()
                .setAttributeSubscription(false)
                .setRpcSubscription(false)
                .setLastActivityTime(System.currentTimeMillis())
                .build(), TransportServiceCallback.EMPTY);
    }

}
