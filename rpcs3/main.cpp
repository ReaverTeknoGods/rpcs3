#include "stdafx.h"
#include "rpcs3.h"
#include "Emu/IdManager.h"
#include "Emu/Io/usio.h"
#include "Emu/System.h"
#include "Emu/Cell/lv2/sys_usbd.h"
#ifdef _WIN32
#include <windows.h>
#endif
#include <Utilities/Thread.h>
#include "Emu/system_utils.hpp"

LOG_CHANNEL(sys_log, "SYS");

// Forward declaration of usio_memory struct (from usio.cpp)
struct usio_memory
{
	std::vector<u8> backup_memory;

	usio_memory() = default;
	usio_memory(const usio_memory&) = delete;
	usio_memory& operator=(const usio_memory&) = delete;

	static constexpr usz page_size = 0x10000;
	static constexpr usz page_count = 0x10;
};

// Ugly hack to make sure USIO backup memory gets saved when we exit via ESC key
// because it skips the deconstructor...
// TODO: find a better solution, calling Exit.Quit and Exit.GracefulShutdown doesn't work as some of the games
// don't seem to exit cleanly and hang instead.
void SaveUsioBackup()
{
	try
	{
		if (g_fxo && g_fxo->is_init<usio_memory>())
		{
			auto& memory = g_fxo->get<usio_memory>();
			if (!memory.backup_memory.empty())
			{
				const std::string backup_path = rpcs3::utils::get_hdd1_dir() + "/caches/usiobackup.bin";

				fs::file usio_backup_file;
				if (usio_backup_file.open(backup_path, fs::create + fs::write + fs::lock))
				{
					const u64 file_size = memory.backup_memory.size();
					usio_backup_file.write(memory.backup_memory.data(), file_size);
					usio_backup_file.trunc(file_size);
					sys_log.notice("USIO backup saved successfully to: %s", backup_path);
				}
				else
				{
					sys_log.error("Failed to save USIO backup file: %s", backup_path);
				}
			}
		}
	}
	catch (const std::exception& e)
	{
		sys_log.error("Exception while saving USIO backup: %s", e.what());
	}
}

#ifdef _WIN32
void InitializeEscapeKeyMonitor()
{
	static bool g_escape_monitor_initialized = false;
	static HANDLE g_escape_thread = nullptr;

	// Only initialize once
	if(g_escape_monitor_initialized)
		return;

	g_escape_monitor_initialized = true;

	// Create the monitoring thread
	g_escape_thread = CreateThread(
	    nullptr,              // Default security attributes
	    0,                    // Default stack size
	    [](LPVOID) -> DWORD { // Thread function
		    while(true)
		    {
			    // Check if ESC key is pressed (virtual key code 0x1B)
			    if(GetAsyncKeyState(VK_ESCAPE) & 0x8000)
			    {
					// Manually save usio backup before exit
					// because the deconstructor won't be called and we need it to save
					SaveUsioBackup();

					ExitProcess(0);
			    }

			    // Small delay to prevent excessive CPU usage
			    Sleep(10);
		    }
		    return 0;
	    },
	    nullptr, // No parameter to thread function
	    0,       // Default creation flags
	    nullptr  // Don't need thread ID
	);

	if(g_escape_thread)
	{
		// Set thread priority to below normal so it doesn't interfere with main execution
		SetThreadPriority(g_escape_thread, THREAD_PRIORITY_BELOW_NORMAL);
	}
}
#endif

int main(int argc, char** argv)
{
#ifdef _WIN32
	InitializeEscapeKeyMonitor();
#endif
	const int exit_code = run_rpcs3(argc, argv);
	sys_log.notice("RPCS3 terminated with exit code %d", exit_code);
	return exit_code;
}
