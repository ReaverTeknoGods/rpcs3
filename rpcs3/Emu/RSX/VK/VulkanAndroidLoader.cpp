#include "stdafx.h"
#include "VulkanAPI.h"

#include "util/logs.hpp"

#include <adrenotools/driver.h>
#include <cstdlib>
#include <dlfcn.h>
#include <mutex>
#include <utility>

namespace vk
{
	namespace
	{
		std::mutex s_vulkan_config_mutex;
		std::once_flag s_vulkan_once;
		bool s_vulkan_initialized = false;
		void* s_vulkan_library = nullptr;
		std::string s_hook_library_dir;
		std::string s_custom_driver_dir;
		std::string s_temporary_dir;

		bool try_initialize_turnip()
		{
			// One UI needs Turnip to propagate its UBWC usage hint to gralloc so
			// SurfaceFlinger interprets swapchain image layouts correctly.
			setenv("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1", 1);

			std::string hook_library_dir;
			std::string custom_driver_dir;
			std::string temporary_dir;
			{
				std::lock_guard lock(s_vulkan_config_mutex);
				hook_library_dir = s_hook_library_dir;
				custom_driver_dir = s_custom_driver_dir;
				temporary_dir = s_temporary_dir;
			}

			if (hook_library_dir.empty() || custom_driver_dir.empty() || temporary_dir.empty())
			{
				rsx_log.warning("Mesa Turnip paths were not configured before Vulkan initialization");
				return false;
			}

			void* library = adrenotools_open_libvulkan(
				RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
				temporary_dir.c_str(), hook_library_dir.c_str(),
				custom_driver_dir.c_str(), "libvulkan_freedreno.so", nullptr, nullptr);
			if (!library)
			{
				rsx_log.warning("libadrenotools could not load Mesa Turnip: %s", dlerror());
				return false;
			}

			auto get_instance_proc_addr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
				dlsym(library, "vkGetInstanceProcAddr"));
			if (!get_instance_proc_addr)
			{
				rsx_log.error("The libadrenotools Vulkan loader has no vkGetInstanceProcAddr entry point");
				dlclose(library);
				return false;
			}

			volkInitializeCustom(get_instance_proc_addr);
			if (!vkCreateInstance || !vkEnumerateInstanceExtensionProperties)
			{
				rsx_log.error("Mesa Turnip did not provide the required Vulkan entry points");
				dlclose(library);
				return false;
			}

			s_vulkan_library = library;
			rsx_log.notice("Using libadrenotools Vulkan loader for packaged Mesa Turnip");
			return true;
		}
	}

	bool configure_android_vulkan(std::string hook_library_dir, std::string custom_driver_dir, std::string temporary_dir)
	{
		if (hook_library_dir.empty() || custom_driver_dir.empty() || temporary_dir.empty())
		{
			return false;
		}

		std::lock_guard lock(s_vulkan_config_mutex);
		if (s_vulkan_initialized)
		{
			return false;
		}

		s_hook_library_dir = std::move(hook_library_dir);
		s_custom_driver_dir = std::move(custom_driver_dir);
		s_temporary_dir = std::move(temporary_dir);
		return true;
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
				rsx_log.error("Could not initialize an Android Vulkan driver: %d", static_cast<s32>(result));
			}
		});

		return s_vulkan_initialized;
	}
}
