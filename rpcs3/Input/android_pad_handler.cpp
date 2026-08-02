#include "stdafx.h"
#include "android_pad_handler.h"

#include "Input/pad_thread.h"

android_pad_handler::on_connect_cb android_pad_handler::s_on_connect = [](const std::shared_ptr<Pad>&)
{
	return false;
};

android_pad_handler::android_pad_handler()
	: PadHandlerBase(pad_handler::keyboard)
{
	m_name_string = "Android Virtual Pad";
	m_max_devices = MAX_GAMEPADS;
	b_has_config = true;
}

void android_pad_handler::set_on_connect_cb(on_connect_cb callback)
{
	s_on_connect = callback ? std::move(callback) : on_connect_cb{[](const std::shared_ptr<Pad>&) { return false; }};
}

bool android_pad_handler::Init()
{
	m_is_init = true;
	return true;
}

void android_pad_handler::init_config(cfg_pad* cfg)
{
	if (cfg)
	{
		cfg->from_default();
	}
}

std::vector<pad_list_entry> android_pad_handler::list_devices()
{
	std::vector<pad_list_entry> devices;
	devices.reserve(MAX_GAMEPADS);

	for (usz index = 1; index <= MAX_GAMEPADS; index++)
	{
		devices.emplace_back(fmt::format("Virtual Pad #%d", index), false);
	}

	return devices;
}

PadHandlerBase::connection android_pad_handler::get_next_button_press(const std::string&, const pad_callback&, const pad_fail_callback&, gui_call_type, const std::vector<std::string>&)
{
	return connection::connected;
}

bool android_pad_handler::bindPadToDevice(std::shared_ptr<Pad> pad)
{
	if (!pad || pad->m_player_id >= g_cfg_input.player.size())
	{
		return false;
	}

	const cfg_player* player_config = g_cfg_input.player[pad->m_player_id];
	if (!player_config || player_config->device.to_string() != "Virtual")
	{
		return false;
	}

	if (!s_on_connect(pad))
	{
		return false;
	}

	m_bindings.emplace_back(std::move(pad), nullptr, nullptr);
	connected_devices++;
	return true;
}

void android_pad_handler::process()
{
}
