#include <vulkan/vulkan.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#ifndef RECRUISER_HOST_TEST
#include <jni.h>
#endif

namespace {
const uint32_t shaderCode[] =
#include "unproject.inc"
;
struct Settings {
    uint32_t width, height, minConfidence, padding;
    float fx, fy, cx, cy;
};
static_assert(sizeof(Settings) == 32);
void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS)
        throw std::runtime_error(std::string(operation) + ": Vulkan status " + std::to_string(result));
}
void validate(const Settings& s) {
    const uint64_t count = uint64_t(s.width) * s.height;
    if (!s.width || !s.height || count > 1048576 || s.minConfidence > 255 ||
        !std::isfinite(s.fx) || !std::isfinite(s.fy) || s.fx <= 0 || s.fy <= 0 ||
        !std::isfinite(s.cx) || !std::isfinite(s.cy))
        throw std::runtime_error("Invalid or oversized depth image");
}
struct Buffer {
    VkDevice device = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    ~Buffer() {
        if (mapped) vkUnmapMemory(device, memory);
        if (buffer) vkDestroyBuffer(device, buffer, nullptr);
        if (memory) vkFreeMemory(device, memory, nullptr);
    }
};
struct Dispatch {
    VkDevice device = VK_NULL_HANDLE;
    VkCommandPool commands = VK_NULL_HANDLE;
    VkDescriptorPool descriptors = VK_NULL_HANDLE;
    ~Dispatch() {
        if (commands) vkDestroyCommandPool(device, commands, nullptr);
        if (descriptors) vkDestroyDescriptorPool(device, descriptors, nullptr);
    }
};
class Engine {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
public:
    ~Engine() {
        if (device) vkDeviceWaitIdle(device);
        if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
        if (shader) vkDestroyShaderModule(device, shader, nullptr);
        if (pipelineLayout) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        if (descriptorLayout) vkDestroyDescriptorSetLayout(device, descriptorLayout, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    }
    void init() {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Recruiser depth unprojection";
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo = &app;
        check(vkCreateInstance(&ci, nullptr, &instance), "Create instance");
        uint32_t n = 0;
        check(vkEnumeratePhysicalDevices(instance, &n, nullptr), "Enumerate devices");
        std::vector<VkPhysicalDevice> devices(n);
        check(vkEnumeratePhysicalDevices(instance, &n, devices.data()), "Read devices");
        for (const auto candidate : devices) {
            uint32_t count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, nullptr);
            std::vector<VkQueueFamilyProperties> props(count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, props.data());
            for (uint32_t i = 0; i < count; ++i) {
                if (props[i].queueCount && (props[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                    physical = candidate; family = i; break;
                }
            }
            if (physical) break;
        }
        if (!physical) throw std::runtime_error("No Vulkan compute device");
#ifdef RECRUISER_HOST_TEST
        VkPhysicalDeviceProperties deviceProperties{};
        vkGetPhysicalDeviceProperties(physical, &deviceProperties);
        std::cout << "Vulkan device: " << deviceProperties.deviceName << '\n';
#endif
        float priority = 1;
        VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        q.queueFamilyIndex = family; q.queueCount = 1; q.pQueuePriorities = &priority;
        VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dc.queueCreateInfoCount = 1; dc.pQueueCreateInfos = &q;
        check(vkCreateDevice(physical, &dc, nullptr, &device), "Create device");
        vkGetDeviceQueue(device, family, 0, &queue);
        VkDescriptorSetLayoutBinding bindings[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = 2; dl.pBindings = bindings;
        check(vkCreateDescriptorSetLayout(device, &dl, nullptr, &descriptorLayout), "Descriptor layout");
        VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Settings)};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1; pl.pSetLayouts = &descriptorLayout;
        pl.pushConstantRangeCount = 1; pl.pPushConstantRanges = &push;
        check(vkCreatePipelineLayout(device, &pl, nullptr, &pipelineLayout), "Pipeline layout");
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        sm.codeSize = sizeof(shaderCode); sm.pCode = shaderCode;
        check(vkCreateShaderModule(device, &sm, nullptr, &shader), "Shader module");
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cp.stage.module = shader; cp.stage.pName = "main";
        cp.layout = pipelineLayout;
        check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cp, nullptr, &pipeline), "Compute pipeline");
    }
    void allocate(Buffer& b, VkDeviceSize bytes) {
        b.device = device;
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = bytes; bc.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        check(vkCreateBuffer(device, &bc, nullptr, &b.buffer), "Create buffer");
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, b.buffer, &req);
        VkPhysicalDeviceMemoryProperties props{};
        vkGetPhysicalDeviceMemoryProperties(physical, &props);
        uint32_t index = props.memoryTypeCount;
        const auto flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        for (uint32_t i = 0; i < props.memoryTypeCount; ++i)
            if ((req.memoryTypeBits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) { index = i; break; }
        if (index == props.memoryTypeCount) throw std::runtime_error("No coherent host-visible Vulkan memory");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size; ma.memoryTypeIndex = index;
        check(vkAllocateMemory(device, &ma, nullptr, &b.memory), "Allocate buffer memory");
        check(vkBindBufferMemory(device, b.buffer, b.memory, 0), "Bind buffer memory");
        check(vkMapMemory(device, b.memory, 0, bytes, 0, &b.mapped), "Map buffer memory");
    }
    std::vector<float> run(const std::vector<uint32_t>& samples, const Settings& settings) {
        validate(settings);
        const size_t count = size_t(settings.width) * settings.height;
        if (samples.size() != count) throw std::runtime_error("Depth sample count mismatch");
        Buffer input, output;
        allocate(input, count * sizeof(uint32_t)); allocate(output, count * 3 * sizeof(float));
        std::memcpy(input.mapped, samples.data(), count * sizeof(uint32_t));
        Dispatch dispatch; dispatch.device = device;
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = 1; dp.poolSizeCount = 1; dp.pPoolSizes = &poolSize;
        check(vkCreateDescriptorPool(device, &dp, nullptr, &dispatch.descriptors), "Descriptor pool");
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = dispatch.descriptors; da.descriptorSetCount = 1; da.pSetLayouts = &descriptorLayout;
        VkDescriptorSet set;
        check(vkAllocateDescriptorSets(device, &da, &set), "Allocate descriptors");
        VkDescriptorBufferInfo buffers[2]{{input.buffer, 0, count * sizeof(uint32_t)},
                                         {output.buffer, 0, count * 3 * sizeof(float)}};
        VkWriteDescriptorSet writes[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = set; writes[i].dstBinding = i; writes[i].descriptorCount = 1;
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[i].pBufferInfo = &buffers[i];
        }
        vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);
        VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pool.queueFamilyIndex = family;
        check(vkCreateCommandPool(device, &pool, nullptr, &dispatch.commands), "Command pool");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = dispatch.commands; ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ca.commandBufferCount = 1;
        VkCommandBuffer cmd;
        check(vkAllocateCommandBuffers(device, &ca, &cmd), "Command buffer");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(cmd, &begin), "Begin commands");
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(settings), &settings);
        vkCmdDispatch(cmd, static_cast<uint32_t>((count + 63) / 64), 1, 1);
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                             0, 1, &barrier, 0, nullptr, 0, nullptr);
        check(vkEndCommandBuffer(cmd), "End commands");
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1; submit.pCommandBuffers = &cmd;
        check(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE), "Submit compute");
        check(vkQueueWaitIdle(queue), "Wait compute");
        std::vector<float> result(count * 3);
        std::memcpy(result.data(), output.mapped, result.size() * sizeof(float));
        return result;
    }
};
std::vector<float> unproject(const std::vector<uint32_t>& samples, const Settings& settings) {
    static std::mutex mutex;
    static std::unique_ptr<Engine> engine;
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!engine) { engine = std::make_unique<Engine>(); engine->init(); }
        return engine->run(samples, settings);
    } catch (...) { engine.reset(); throw; }
}
}

#ifndef RECRUISER_HOST_TEST
extern "C" JNIEXPORT jfloatArray JNICALL
Java_mba_robin_recruiser_capture_VulkanDepthUnprojector_unprojectNative(
    JNIEnv* env, jobject, jshortArray depth, jbyteArray confidence, jint width, jint height,
    jfloat fx, jfloat fy, jfloat cx, jfloat cy, jint minConfidence) {
    try {
        Settings settings{uint32_t(width), uint32_t(height), uint32_t(minConfidence), 0, fx, fy, cx, cy};
        validate(settings);
        const size_t count = size_t(width) * height;
        if (!depth || !confidence || size_t(env->GetArrayLength(depth)) != count ||
            size_t(env->GetArrayLength(confidence)) != count) throw std::runtime_error("Invalid depth arrays");
        std::vector<jshort> mm(count); std::vector<jbyte> c(count);
        env->GetShortArrayRegion(depth, 0, count, mm.data());
        env->GetByteArrayRegion(confidence, 0, count, c.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<uint32_t> packed(count);
        for (size_t i = 0; i < count; ++i) packed[i] = uint16_t(mm[i]) | (uint32_t(uint8_t(c[i])) << 16);
        auto points = unproject(packed, settings);
        auto out = env->NewFloatArray(points.size());
        if (out) env->SetFloatArrayRegion(out, 0, points.size(), points.data());
        return out;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return nullptr;
    }
}
#else
int main() {
    try {
        // Exercise boundary depths, unsigned confidence, uneven dispatch and real calibration.
        Settings s{37, 29, 128, 0, 124.5f, 127.25f, 17.3f, 14.2f};
        std::vector<uint32_t> input(s.width * s.height);
        const uint32_t depths[]{0, 99, 100, 1000, 10000, 10001, 65535};
        for (size_t i = 0; i < input.size(); ++i)
            input[i] = depths[i % 7] | (uint32_t(i % 3 == 0 ? 127 : i % 3 == 1 ? 128 : 255) << 16);
        for (int repeat = 0; repeat < 2; ++repeat) {
            const auto result = unproject(input, s);
            for (size_t i = 0; i < input.size(); ++i) {
                const uint32_t mm = input[i] & 65535, confidence = input[i] >> 16;
                const bool valid = mm >= 100 && mm <= 10000 && confidence >= s.minConfidence;
                const float d = mm * .001f;
                const float expected[]{(float(i % s.width) - s.cx) * d / s.fx,
                    -(float(i / s.width) - s.cy) * d / s.fy, -d};
                for (int j = 0; j < 3; ++j)
                    if (valid ? !std::isfinite(result[i * 3 + j]) || std::abs(result[i * 3 + j] - expected[j]) > 1e-5f
                              : !std::isnan(result[i * 3 + j]))
                        throw std::runtime_error("GPU/reference mismatch at sample " + std::to_string(i));
            }
        }
        bool rejected = false;
        try { unproject(input, Settings{0, 29, 128, 0, 1, 1, 0, 0}); }
        catch (const std::exception&) { rejected = true; }
        if (!rejected) throw std::runtime_error("Invalid dimensions accepted");
        std::cout << "Vulkan parity passed: 1073 samples, 2 dispatches, invalid dimensions rejected\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
#endif
