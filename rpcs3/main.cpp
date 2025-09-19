#include "stdafx.h"
#include "rpcs3.h"
#ifdef _WIN32
#include <windows.h>
#endif

LOG_CHANNEL(sys_log, "SYS");

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
				    // ESC is pressed, exit the process immediately
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
