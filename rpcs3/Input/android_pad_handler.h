#pragma once

#include "Emu/Io/PadHandler.h"

#include <functional>

// Android delivers controller and touch-overlay state through the JNI frontend.
// The frontend owns the Pad state update, while this handler gives the core a
// real, connected pad instead of the Android NullPad fallback.
class android_pad_handler final : public PadHandlerBase
{
public:
	using on_connect_cb = std::function<bool(const std::shared_ptr<Pad>&)>;

	android_pad_handler();

	static void set_on_connect_cb(on_connect_cb callback);

	bool Init() override;
	void init_config(cfg_pad* cfg) override;
	std::vector<pad_list_entry> list_devices() override;
	connection get_next_button_press(const std::string&, const pad_callback&, const pad_fail_callback&, gui_call_type, const std::vector<std::string>&) override;
	bool bindPadToDevice(std::shared_ptr<Pad> pad) override;
	void process() override;

private:
	static on_connect_cb s_on_connect;
};
