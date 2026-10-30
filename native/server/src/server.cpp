// The openDAQ server of the Device Server app.
//
// It holds one openDAQ instance whose root device is the phone itself
// (AndroidDeviceModule's android://), with the sensors as its channels, and
// function blocks on its root device, such as the
// Universal renderer, and serves it over openDAQ's native protocol, announced
// with mDNS, so openDAQ clients on the network can add the phone. There is no
// user interface: the Kotlin service starts and stops it through JNI and reads
// a short status for its notification.

#include <opendaq/opendaq.h>

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <mutex>
#include <set>
#include <string>
#include <vector>

using namespace daq;

namespace
{

constexpr const char* kTag = "SensorServer";
constexpr Int kPollingPeriodMs = 2;

std::mutex lock;
InstancePtr instance;
/// The sensor devices added at start. Their active channels are the sensors
/// the status lists.
std::vector<DevicePtr> sensors;

std::string text(JNIEnv* env, jstring value)
{
    if (!value)
        return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<std::string> texts(JNIEnv* env, jobjectArray values)
{
    std::vector<std::string> out;
    const jsize count = values ? env->GetArrayLength(values) : 0;
    for (jsize i = 0; i < count; i++)
    {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        out.push_back(text(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}

std::string quote(const std::string& value)
{
    std::string out = "\"";
    for (const char c : value)
    {
        if (c == '"' || c == '\\')
            out += '\\';
        if (c == '\n')
            out += "\\n";
        else if (static_cast<unsigned char>(c) >= 0x20)
            out += c;
    }
    return out + "\"";
}

/// Loads a module library by file name, from the app's native libraries.
/// Android keeps them inside the APK, where the module manager's directory
/// scan cannot see them.
void loadModule(const std::string& library)
{
    void* handle = dlopen(library.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle)
        throw std::runtime_error("Could not load " + library + ": " + dlerror());
    using CreateModule = ErrCode (*)(IModule**, IContext*);
    auto create = reinterpret_cast<CreateModule>(dlsym(handle, "createModule"));
    if (!create)
        throw std::runtime_error(library + " is not an openDAQ module");
    IModule* raw = nullptr;
    checkErrorInfo(create(&raw, instance.getContext()));
    instance.getModuleManager().addModule(ModulePtr::Adopt(raw));
}

/// The number of openDAQ clients connected to the server. A client of the
/// native protocol opens two connections, one for configuration and one for
/// streaming, so a client is a configuration connection, or a streaming
/// connection from a host without one.
int clientCount()
{
    try
    {
        const PropertyObjectPtr clients = instance.getRootDevice().getInfo().getPropertyValue("activeClientConnections");
        size_t count = 0;
        std::set<std::string> configuring;
        std::vector<std::string> streaming;
        for (const auto& property : clients.getAllProperties())
        {
            const ConnectedClientInfoPtr client = clients.getPropertyValue(property.getName());
            std::string host = client.getAddress().toStdString();
            host = host.substr(0, host.rfind(':'));
            if (client.getProtocolType() == ProtocolType::Streaming)
            {
                streaming.push_back(host);
            }
            else
            {
                configuring.insert(host);
                count++;
            }
        }
        for (const auto& host : streaming)
            if (!configuring.count(host))
                count++;
        return static_cast<int>(count);
    }
    catch (...)
    {
        return 0;
    }
}

}  // namespace

/// Starts openDAQ and its server, with the sensor devices and then the
/// function blocks of the given types on the root device. `identity` is the
/// phone's name, maker, model and serial number. Returns null, or the reason
/// it could not start.
extern "C" JNIEXPORT jstring JNICALL Java_io_opendaq_sensorserver_NativeServer_start(JNIEnv* env,
                                                                                     jclass,
                                                                                     jstring moduleDirectory,
                                                                                     jobjectArray modules,
                                                                                     jobjectArray devices,
                                                                                     jobjectArray functionBlocks,
                                                                                     jobjectArray identity,
                                                                                     jint port)
{
    std::lock_guard guard(lock);
    if (instance.assigned())
        return nullptr;
    try
    {
        const auto id = texts(env, identity);
        const std::string name = id.size() > 0 ? id[0] : "Phone";
        const std::string serial = id.size() > 3 ? id[3] : "";

        // The mDNS announcement needs the maker and the serial number.
        auto info = DeviceInfo("", name);
        info.setManufacturer(id.size() > 1 ? id[1] : "");
        info.setModel(id.size() > 2 ? id[2] : "");
        info.setSerialNumber(serial);
        auto makeBuilder = [&](bool announce)
        {
            auto builder = InstanceBuilder()
                               .setModulePath(text(env, moduleDirectory))
                               .setDefaultRootDeviceInfo(info)
                               .setDefaultRootDeviceLocalId(serial.empty() ? "Phone" : "Phone" + serial.substr(0, 8));
            if (announce)
                builder.addDiscoveryServer("mdns");
            return builder;
        };
        // openDAQ's mDNS discovery server refuses to start without a network
        // interface. The server then runs without the announcement and can be
        // added by connection string once the phone is on a network.
        bool announce = true;
        try
        {
            instance = makeBuilder(true).build();
        }
        catch (const std::exception& error)
        {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Starting without mDNS: %s", error.what());
            instance = makeBuilder(false).build();
            announce = false;
        }
        // The default root device keeps its own name whatever the info says.
        instance.getRootDevice().setName(name);

        // A module the app was built without, such as the function blocks
        // module, is skipped: the phone is served without its blocks.
        for (const auto& library : texts(env, modules))
        {
            try
            {
                loadModule(library);
            }
            catch (const std::exception& error)
            {
                __android_log_print(ANDROID_LOG_WARN, kTag, "Skipped module %s: %s", library.c_str(), error.what());
            }
        }

        sensors.clear();
        for (const auto& connection : texts(env, devices))
        {
            try
            {
                // The phone device becomes the root, so its channels sit
                // directly in the device clients see, under the phone's name.
                if (connection.rfind("android://", 0) == 0 && sensors.empty())
                {
                    PropertyObjectPtr config;
                    for (const auto& [typeId, type] : instance.getAvailableDeviceTypes())
                        if (type.getConnectionStringPrefix() == "android")
                            config = type.createDefaultConfig();
                    if (config.assigned())
                    {
                        config.setPropertyValue("Name", String(name));
                        config.setPropertyValue("LocalId", String(serial.empty() ? "Phone" : "Phone" + serial.substr(0, 8)));
                        config.setPropertyValue("SerialNumber", String(serial));
                    }
                    instance.setRootDevice(connection, config);
                    sensors.push_back(instance.getRootDevice());
                    continue;
                }
                sensors.push_back(instance.addDevice(connection));
            }
            catch (const std::exception& error)
            {
                // A sensor the phone lacks is skipped.
                __android_log_print(ANDROID_LOG_WARN, kTag, "Skipped %s: %s", connection.c_str(), error.what());
            }
        }

        // Added after the sensors, so a client can connect the blocks' input
        // ports to the sensors' signals.
        for (const auto& type : texts(env, functionBlocks))
        {
            try
            {
                instance.addFunctionBlock(type);
            }
            catch (const std::exception& error)
            {
                __android_log_print(ANDROID_LOG_WARN, kTag, "Skipped function block %s: %s", type.c_str(), error.what());
            }
        }

        auto config = instance.getAvailableServerTypes().get("OpenDAQNativeStreaming").createDefaultConfig();
        config.setPropertyValue("NativeStreamingPort", static_cast<Int>(port));
        // The server collects and sends the signals' packets every 20 ms by
        // default, which delivers a renderer's plots in clumps, about 50 a
        // second whatever its frame rate. Every 2 ms keeps up with 240.
        config.setPropertyValue("StreamingDataPollingPeriod", kPollingPeriodMs);
        auto server = instance.addServer("OpenDAQNativeStreaming", config);
        if (announce)
            server.enableDiscovery();
        __android_log_print(ANDROID_LOG_INFO, kTag, "Serving %zu sensor devices on port %d", sensors.size(), port);
        return nullptr;
    }
    catch (const std::exception& error)
    {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Start failed: %s", error.what());
        sensors.clear();
        instance.release();
        return env->NewStringUTF(error.what());
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_opendaq_sensorserver_NativeServer_stop(JNIEnv*, jclass)
{
    std::lock_guard guard(lock);
    if (!instance.assigned())
        return;
    try
    {
        for (const auto& server : instance.getServers())
            instance.removeServer(server);
    }
    catch (const std::exception& error)
    {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Stopping the server: %s", error.what());
    }
    sensors.clear();
    instance.release();
    __android_log_print(ANDROID_LOG_INFO, kTag, "Stopped");
}

/// `{"running": bool, "sensors": [names of active channels], "clients": n}`, for the notification
/// and the control screen.
extern "C" JNIEXPORT jstring JNICALL Java_io_opendaq_sensorserver_NativeServer_status(JNIEnv* env, jclass)
{
    std::lock_guard guard(lock);
    std::string out = "{\"running\":";
    out += instance.assigned() ? "true" : "false";
    out += ",\"sensors\":[";
    bool any = false;
    for (const auto& device : sensors)
    {
        try
        {
            for (const auto& channel : device.getChannels())
            {
                if (!channel.getActive())
                    continue;
                out += (any ? "," : "") + quote(channel.getName().toStdString());
                any = true;
            }
        }
        catch (const std::exception& error)
        {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Reading the channels: %s", error.what());
        }
    }
    // The Dashboard's addresses and PIN, for the control screen.
    std::string dashboard;
    if (instance.assigned())
    {
        try
        {
            for (const auto& block : instance.getRootDevice().getFunctionBlocks())
            {
                if (block.getFunctionBlockType().getId() != "UniversalDashboard")
                    continue;
                const std::string address = static_cast<std::string>(block.getPropertyValue("WebAddress").asPtr<IString>());
                const std::string pin = static_cast<std::string>(block.getPropertyValue("Pin").asPtr<IString>());
                dashboard = ",\"dashboard\":{\"address\":" + quote(address) + ",\"pin\":" + quote(pin) + "}";
                break;
            }
        }
        catch (const std::exception& error)
        {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Reading the dashboard: %s", error.what());
        }
    }
    out += "],\"clients\":" + std::to_string(instance.assigned() ? clientCount() : 0) + dashboard + "}";
    return env->NewStringUTF(out.c_str());
}
