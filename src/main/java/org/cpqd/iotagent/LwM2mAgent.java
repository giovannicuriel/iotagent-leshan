package org.cpqd.iotagent;


import br.com.dojot.kafka.Manager;
import com.google.gson.JsonElement;
import com.google.gson.*;
import com.mashape.unirest.http.*;

import java.net.HttpURLConnection;
import java.util.*;

import org.apache.log4j.Logger;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeDecoder;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.core.response.WriteResponse;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.californium.impl.LeshanServer;
import org.eclipse.leshan.server.model.LwM2mModelProvider;
import org.eclipse.leshan.server.observation.ObservationListener;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.json.JSONObject;


// TODO(jsiloto) Add decent logging system

public class LwM2mAgent implements Runnable {
    private Logger mLogger = Logger.getLogger(LwM2mAgent.class);

    private String imageManagerUrl;
    private ImageDownloader imageDownloader;
    private DeviceManager deviceManager;
    private LwM2mHandler requestHandler;
    private Gson gson;
    private LeshanServer server;
    private LwM2mModelProvider modelProvider;

    private static HttpURLConnection con;
    private final static String[] modelPaths = new String[]{"5000.xml"};
    private Manager mIotaManager;


    // *********** Instance Initialization *************** //
    LwM2mAgent(String deviceManagerUrl, String imageManagerUrl) {
        this.imageManagerUrl = imageManagerUrl;
        this.gson = createGson();
        this.mIotaManager = new Manager();

        // Define model provider
        List<ObjectModel> models = ObjectLoader.loadDefault();
        models.addAll(ObjectLoader.loadDdfResources("/models/", modelPaths));
        DynamicModelProvider dynamicModelProvider = new DynamicModelProvider(models);

        modelProvider = dynamicModelProvider;
        imageDownloader = new ImageDownloader(imageManagerUrl);
        deviceManager = new DeviceManager(deviceManagerUrl, dynamicModelProvider);

        this.mIotaManager.addCallback("create", this::on_create);
        this.mIotaManager.addCallback("update", this::on_update);
        this.mIotaManager.addCallback("remove", this::on_remove);
        this.mIotaManager.addCallback("configure", this::on_actuate);
        this.mIotaManager.addCallback("template.update", this::on_template_update);
    }

    private static Gson createGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeHierarchyAdapter(LwM2mNode.class, new LwM2mNodeSerializer());
        gsonBuilder.registerTypeHierarchyAdapter(LwM2mNode.class, new LwM2mNodeDeserializer());
        gsonBuilder.setDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        Gson thiGson = gsonBuilder.create();
        return thiGson;
    }

    private static final Map<String, Integer[]> lampLwm2m = createMap();

    private static Map<String, Integer[]> createMap() {
        Map<String, Integer[]> lampMapping = new HashMap<String, Integer[]>();
        lampMapping.put("name", new Integer[]{5000, 0, 0});
        lampMapping.put("voltage", new Integer[]{5000, 0, 1});
        lampMapping.put("luminosity", new Integer[]{5000, 0, 2});
        lampMapping.put("firmware", new Integer[]{5, 0, 1});
        return lampMapping;
    }

    // ********* Methods ****************** //

    private void registerNewDevice(Registration registration) {

        mLogger.trace(String.format("Trying to register: %s", registration.getId()));

        //Get ID
        String service = "admin";

        // Check device manager if device exists, if not drop
        String DeviceModel = requestHandler.ReadResource(registration, 3, 0, 1);
        String SerialNumber = requestHandler.ReadResource(registration, 3, 0, 2);
        mLogger.debug(DeviceModel + " / " + SerialNumber);
        String Lwm2mId = registration.getId();
        JsonElement deviceJson = deviceManager.GetDeviceFromDeviceManager(service, DeviceModel, SerialNumber);
        Device device = new Device(deviceJson);
        deviceManager.RegisterDevice(device, service, Lwm2mId, DeviceModel, SerialNumber, registration);


        // Register listeners for dynamic data
        for (DeviceAttribute attr : device.attributes) {
            if (attr.type.equals("dynamic")) {
                Integer[] path = attr.getLwm2mPath();
                requestHandler.ObserveResource(registration, path[0], path[1], path[2]);
            }
        }

        // TODO(jsiloto): This should go into a loggin system
        mLogger.debug("new device: " + registration.getEndpoint());
        for (int i = 0; i < registration.getObjectLinks().length; i++) {
            mLogger.debug(registration.getObjectLinks()[i]);
        }
    }

    private void updateFW(Registration registration, String newFwVersion, String templateLabel, String service) {
        // Get device current FW version
        String currentFwVersion = requestHandler.ReadResource(registration, 3, 0, 3);

        // If Version has changed Update
        if (!currentFwVersion.equals(newFwVersion)) {
            mLogger.debug("Update Succesfull");
            String fileUrl = imageDownloader.ImageUrl(service, templateLabel, newFwVersion);
            requestHandler.WriteResource(registration, 5, 0, 1, fileUrl);
        } else {
            mLogger.debug("Device already Up-to-date");
        }
    }


    // *********** Run Server *************** //
    private Integer on_create(JSONObject message) {
        mLogger.debug("on_create: " + message.toString());
        JsonElement o = new JsonParser().parse(message.toString());
        String service = o.getAsJsonObject().get("meta").getAsJsonObject().get("service").getAsString();
        Device device = new Device(o.getAsJsonObject().get("data"));
        deviceManager.RegisterModel(device);
        return 0;
    }


    private Integer on_update(JSONObject message) {
        mLogger.debug("on_update: " + message.toString());

        JsonElement o = new JsonParser().parse(message.toString());
        Device device = new Device(o.getAsJsonObject().get("data"));
        String service = o.getAsJsonObject().get("meta").getAsJsonObject().get("service").getAsString();
        deviceManager.RegisterModel(device);

        // Retrieve device id
        String id = device.deviceId;
        Registration registration = deviceManager.getDeviceRegistration(id);
        if (registration == null) {
            return -1;
        }

        mLogger.debug(registration);

        // Get device label and new FW Version
        String newFwVersion = device.getStaticValue("fw_version");
        String templateId = device.getTemplateId("fw_version");
        String templateLabel = deviceManager.GetTemplateLabel(service, templateId);

        updateFW(registration, newFwVersion, templateLabel, service);

        return 0;
    }

    private Integer on_remove(JSONObject message) {
        mLogger.debug("on_remove: " + message.toString());
        JsonObject o = new JsonParser().parse(message.toString()).getAsJsonObject();
        String deviceId = o.get("data").getAsJsonObject().get("id").getAsString();
        Registration registration = deviceManager.getDeviceRegistration(deviceId);
        deviceManager.DeregisterDevice(registration.getId());
        return 0;
    }

    private Object getObjectFromResourceJson(ResourceModel.Type type, JsonPrimitive attr) {
        switch (type) {
            case STRING:
                return attr.getAsString();
            case FLOAT:
                return attr.getAsDouble();
            case BOOLEAN:
                return attr.getAsBoolean();
            case INTEGER:
                return attr.getAsInt();
            default:
                return attr.getAsString();
        }
    }


    private Integer on_template_update(JSONObject message) {
        mLogger.debug("on_template_update: " + message.toString());
        JsonObject o = new JsonParser().parse(message.toString()).getAsJsonObject();

        JsonObject data = o.get("data").getAsJsonObject();
        String service = o.get("meta").getAsJsonObject().get("service").getAsString();


        // get new fw version

        LinkedList<DeviceAttribute> attrs = Device.getAttributeListFromTemplate(data.get("template").getAsJsonObject().get("attrs"));
        String newFwVersion = Device.getStaticValue(attrs, "fw_version");
        String templateLabel = data.get("template").getAsJsonObject().get("label").getAsString();


        //iterate over each affected device
        //if device exists and is connected
        for (JsonElement deviceId : data.get("affected").getAsJsonArray()) {
            mLogger.debug(String.format("Trying to update: %s with version: %s", deviceId.getAsString(), newFwVersion));
            Registration registration = deviceManager.getDeviceRegistration(deviceId.getAsString());
            if (registration == null) {
                mLogger.debug("No such device");
                continue;
            }
            updateFW(registration, newFwVersion, templateLabel, service);

        }
        return 0;
    }

    private Integer on_actuate(JSONObject message) {
        mLogger.debug("on_actuate: " + message.toString());
        JsonElement o = new JsonParser().parse(message.toString());
        String service = o.getAsJsonObject().get("meta").getAsJsonObject().get("service").getAsString();

        JsonElement data = o.getAsJsonObject().get("data");
        JsonObject attrs = data.getAsJsonObject().get("attrs").getAsJsonObject();
        String deviceId = data.getAsJsonObject().get("id").getAsString();
        Registration registration = deviceManager.getDeviceRegistration(deviceId);


        for (Map.Entry<String, JsonElement> attr : attrs.entrySet()) {
            Integer[] path = deviceManager.getPathFromLabel(attr.getKey());
            if (path != null) {
                ResourceModel.Type type = modelProvider.getObjectModel(registration).getResourceModel(path[0], path[2]).type;
                Object value = getObjectFromResourceJson(type, attr.getValue().getAsJsonPrimitive());
                requestHandler.WriteResource(registration, path[0], path[1], path[2], value);
            }
        }

        return 0;

    }


    private final RegistrationListener registrationListener = new RegistrationListener() {
        public void registered(Registration registration, Registration previousReg,
                               Collection<Observation> previousObsersations) {
            registerNewDevice(registration);
        }

        public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
            if (deviceManager.getLwm2mRegistration(updatedReg.getId()) == null) {
                registerNewDevice(updatedReg);
            }
        }

        public void unregistered(Registration registration, Collection<Observation> observations, boolean expired,
                                 Registration newReg) {
            mLogger.debug("device left: " + registration.getEndpoint());
            deviceManager.DeregisterDevice(registration.getId());
        }
    };

    private final ObservationListener observationListener = new ObservationListener() {
        @Override
        public void cancelled(Observation observation) {
        }

        @Override
        public void onResponse(Observation observation, Registration registration, ObserveResponse response) {
            JsonElement element = gson.toJsonTree(response.getContent());
            mLogger.debug("Received notification from [" + observation.getPath() + "] containing value:" + element);
            JsonObject attrs = new JsonObject();
            String label = deviceManager.getLabelFromPath(observation.getPath().toString());
            attrs.add(label, element.getAsJsonObject().get("value"));
            String deviceId = deviceManager.getDeviceId(observation.getRegistrationId());
            // String service = deviceManager.getDeviceService(deviceId);
            String service = "admin";
            mIotaManager.updateAttrs(deviceId, service, new JSONObject(attrs.toString()), null);
        }

        @Override
        public void onError(Observation observation, Registration registration, Exception error) {
            mLogger.error("Unable to handle notification of" + observation.getRegistrationId().toString() + ": " + observation.getPath());
        }

        @Override
        public void newObservation(Observation observation, Registration registration) {
        }
    };


    @Override
    public void run() {
        try {
            LeshanServerBuilder builder = new LeshanServerBuilder();

            // Set encoder/decoders
            builder.setEncoder(new DefaultLwM2mNodeEncoder());
            LwM2mNodeDecoder decoder = new DefaultLwM2mNodeDecoder();
            builder.setDecoder(decoder);

            // Define model provider
            builder.setObjectModelProvider(modelProvider);


            // Start Server
            server = builder.build();
            server.start();

            // Add Registration Treatment
            server.getRegistrationService().addListener(registrationListener);
            server.getObservationService().addListener(observationListener);

            // Initialize Request Handler
            requestHandler = new LwM2mHandler(server, gson);

        } catch (Exception e) {
            e.printStackTrace();
            mLogger.error(e);
        }
    }


}
