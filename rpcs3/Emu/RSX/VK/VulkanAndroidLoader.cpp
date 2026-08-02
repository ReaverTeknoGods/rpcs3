#include "stdafx.h"
#include "VulkanAPI.h"

#include "util/logs.hpp"

#include <cstddef>
#include <cstring>
#include <dlfcn.h>
#include <mutex>

namespace vk
{
	namespace
	{
		struct hw_module_t;
		struct hw_device_t;

		struct hw_module_methods_t
		{
			int (*open)(const hw_module_t*, const char*, hw_device_t**);
		};

		struct hw_device_t
		{
			u32 tag;
			u32 version;
			hw_module_t* module;
			u64 reserved[12];
			int (*close)(hw_device_t*);
		};

		struct hw_module_t
		{
			u32 tag;
			u16 module_api_version;
			u16 hal_api_version;
			const char* id;
			const char* name;
			const char* author;
			hw_module_methods_t* methods;
			void* dso;
			u64 reserved[25];
		};

		struct hwvulkan_device_t
		{
			hw_device_t common;
			PFN_vkEnumerateInstanceExtensionProperties enumerate_instance_extensions;
			PFN_vkCreateInstance create_instance;
			PFN_vkGetInstanceProcAddr get_instance_proc_addr;
		};

		static_assert(sizeof(hw_module_t) == 248);
		static_assert(sizeof(hw_device_t) == 120);
		static_assert(offsetof(hwvulkan_device_t, get_instance_proc_addr) == 136);

		std::once_flag s_vulkan_once;
		bool s_vulkan_initialized = false;
		void* s_turnip_library = nullptr;
		hwvulkan_device_t* s_turnip_device = nullptr;

		bool try_initialize_turnip()
		{
			void* library = dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW);
			if (!library)
			{
				rsx_log.warning("Mesa Turnip is unavailable: %s", dlerror());
				return false;
			}

			auto* module = static_cast<hw_module_t*>(dlsym(library, "HMI"));
			if (!module || !module->id || std::strcmp(module->id, "vulkan") ||
				!module->methods || !module->methods->open)
			{
				rsx_log.error("Mesa Turnip does not expose a valid Android Vulkan HAL module");
				dlclose(library);
				return false;
			}

			hwvulkan_device_t* device = nullptr;
			if (module->methods->open(module, "vk0", reinterpret_cast<hw_device_t**>(&device)) ||
				!device || !device->get_instance_proc_addr)
			{
				rsx_log.error("Mesa Turnip failed to open its Android Vulkan HAL device");
				dlclose(library);
				return false;
			}

			volkInitializeCustom(device->get_instance_proc_addr);
			if (!vkCreateInstance || !vkEnumerateInstanceExtensionProperties)
			{
				rsx_log.error("Mesa Turnip did not provide the required Vulkan entry points");
				if (device->common.close)
				{
					device->common.close(&device->common);
				}
				dlclose(library);
				return false;
			}

			s_turnip_library = library;
			s_turnip_device = device;
			rsx_log.notice("Using packaged Mesa Turnip Vulkan driver");
			return true;
		}
	}

	bool initialize_android_vulkan()
	{
		std::call_once(s_vulkan_once, []
		{
			if (try_initialize_turnip())
			{
				s_vulkan_initialized = true;
				return;
			}

			const VkResult result = volkInitialize();
			if (result == VK_SUCCESS)
			{
				s_vulkan_initialized = true;
				rsx_log.notice("Using Android system Vulkan driver");
			}
			else
			{
				rsx_log.error("Could not initialize an Android Vulkan driver: %d", result);
			}
		});

		return s_vulkan_initialized;
	}
}
