// v406 USIO emulator

#include "stdafx.h"
#include "usio.h"
#include "Input/pad_thread.h"
#include "Emu/Io/usio_config.h"
#include "Emu/IdManager.h"
#ifdef _WIN32
#include <windows.h>
#endif

LOG_CHANNEL(usio_log, "USIO");

// TeknoParrot shared memory globals
#ifdef _WIN32
static void* g_teknoparrot_file_mapping = nullptr;
static void* g_teknoparrot_view_ptr = nullptr;
#endif
static bool g_coin_pressed_prev = false;
// Card-entry edge-detect, one per supported player (P1=byte31, P2=30, P3=29, P4=28)
static u8 g_card_state_prev[4] = {0, 0, 0, 0};

#ifdef __ANDROID__
struct android_arcade_input
{
	u64 control = 0;
	std::array<u8, 7> analog{};
	std::array<u8, 4> rotary{};
	u8 coin = 0;
	u8 test = 0;
	u8 card = 0;
};

static std::mutex g_android_arcade_mutex;
static android_arcade_input g_android_arcade_input;

void usio_set_android_arcade_input(u64 control, const std::array<u8, 7>& analog,
	const std::array<u8, 4>& rotary, bool coin, bool test, bool card)
{
	std::lock_guard lock(g_android_arcade_mutex);
	g_android_arcade_input.control = control;
	g_android_arcade_input.analog = analog;
	g_android_arcade_input.rotary = rotary;
	g_android_arcade_input.coin = coin;
	g_android_arcade_input.test = test ? 0x80 : 0;
	g_android_arcade_input.card = card;
}

static android_arcade_input get_android_arcade_input()
{
	std::lock_guard lock(g_android_arcade_mutex);
	return g_android_arcade_input;
}
#endif

template <>
void fmt_class_string<usio_btn>::format(std::string& out, u64 arg)
{
	format_enum(out, arg, [](usio_btn value)
	{
		switch (value)
		{
		case usio_btn::test: return "Test";
		case usio_btn::coin: return "Coin";
		case usio_btn::service: return "Service";
		case usio_btn::enter: return "Enter/Start";
		case usio_btn::up: return "Up";
		case usio_btn::down: return "Down";
		case usio_btn::left: return "Left";
		case usio_btn::right: return "Right";
		case usio_btn::taiko_hit_side_left: return "Taiko Hit Side Left";
		case usio_btn::taiko_hit_side_right: return "Taiko Hit Side Right";
		case usio_btn::taiko_hit_center_left: return "Taiko Hit Center Left";
		case usio_btn::taiko_hit_center_right: return "Taiko Hit Center Right";
		case usio_btn::tekken_button1: return "Tekken Button 1";
		case usio_btn::tekken_button2: return "Tekken Button 2";
		case usio_btn::tekken_button3: return "Tekken Button 3";
		case usio_btn::tekken_button4: return "Tekken Button 4";
		case usio_btn::tekken_button5: return "Tekken Button 5";
		case usio_btn::card_tapping: return "Card Tapping";
		case usio_btn::count: return "Count";
		}

		return unknown;
	});
}

struct usio_memory
{
	std::vector<u8> backup_memory;
	std::array<std::array<u8, 0x40>, g_cfg_usio.players.size()> card_data{};

	usio_memory() = default;
	usio_memory(const usio_memory&) = delete;
	usio_memory& operator=(const usio_memory&) = delete;

	void init()
	{
		backup_memory.clear();
		backup_memory.resize(page_size * page_count);
	}

	static constexpr usz page_size = 0x10000;
	static constexpr usz page_count = 0x10;
};

usb_device_usio::usb_device_usio(const std::array<u8, 7>& location)
	: usb_device_emulated(location)
{
	// Initialize dependencies
	g_fxo->need<usio_memory>();

	device = UsbDescriptorNode(USB_DESCRIPTOR_DEVICE,
		UsbDeviceDescriptor{
			.bcdUSB             = 0x0110,
			.bDeviceClass       = 0xff,
			.bDeviceSubClass    = 0x00,
			.bDeviceProtocol    = 0xff,
			.bMaxPacketSize0    = 0x8,
			.idVendor           = 0x0b9a,
			.idProduct          = 0x0900,
			.bcdDevice          = 0x0900,
			.iManufacturer      = 0x01,
			.iProduct           = 0x02,
			.iSerialNumber      = 0x00,
			.bNumConfigurations = 0x01});

	auto& config0 = device.add_node(UsbDescriptorNode(USB_DESCRIPTOR_CONFIG,
		UsbDeviceConfiguration{
			.wTotalLength        = 39,
			.bNumInterfaces      = 0x01,
			.bConfigurationValue = 0x01,
			.iConfiguration      = 0x00,
			.bmAttributes        = 0xc0,
			.bMaxPower           = 0x32 // ??? 100ma
		}));

	config0.add_node(UsbDescriptorNode(USB_DESCRIPTOR_INTERFACE,
		UsbDeviceInterface{
			.bInterfaceNumber   = 0x00,
			.bAlternateSetting  = 0x00,
			.bNumEndpoints      = 0x03,
			.bInterfaceClass    = 0x00,
			.bInterfaceSubClass = 0x00,
			.bInterfaceProtocol = 0x00,
			.iInterface         = 0x00}));

	config0.add_node(UsbDescriptorNode(USB_DESCRIPTOR_ENDPOINT,
		UsbDeviceEndpoint{
			.bEndpointAddress = 0x01,
			.bmAttributes     = 0x02,
			.wMaxPacketSize   = 0x0040,
			.bInterval        = 0x00}));

	config0.add_node(UsbDescriptorNode(USB_DESCRIPTOR_ENDPOINT,
		UsbDeviceEndpoint{
			.bEndpointAddress = 0x82,
			.bmAttributes     = 0x02,
			.wMaxPacketSize   = 0x0040,
			.bInterval        = 0x00}));

	config0.add_node(UsbDescriptorNode(USB_DESCRIPTOR_ENDPOINT,
		UsbDeviceEndpoint{
			.bEndpointAddress = 0x83,
			.bmAttributes     = 0x03,
			.wMaxPacketSize   = 0x0008,
			.bInterval        = 16}));

#ifdef _WIN32
	// Initialize TeknoParrot shared memory
	if (!g_teknoparrot_file_mapping)
	{
		g_teknoparrot_file_mapping = CreateFileMappingA(
			INVALID_HANDLE_VALUE,
			nullptr,
			PAGE_READWRITE,
			0,
			64,
			"TeknoParrot_JvsState"
		);

		if (g_teknoparrot_file_mapping)
		{
			g_teknoparrot_view_ptr = MapViewOfFile(
				g_teknoparrot_file_mapping,
				FILE_MAP_ALL_ACCESS,
				0,
				0,
				64
			);

			if (g_teknoparrot_view_ptr)
				usio_log.notice("TeknoParrot shared memory initialized successfully");
			else
				usio_log.error("Failed to map TeknoParrot shared memory view");
		}
		else
		{
			usio_log.error("Failed to create TeknoParrot shared memory mapping");
		}
	}
#endif

	load_backup();
}

usb_device_usio::~usb_device_usio()
{
	save_backup();

#ifdef _WIN32
	if (g_teknoparrot_view_ptr)
	{
		UnmapViewOfFile(g_teknoparrot_view_ptr);
		g_teknoparrot_view_ptr = nullptr;
	}
	if (g_teknoparrot_file_mapping)
	{
		CloseHandle(g_teknoparrot_file_mapping);
		g_teknoparrot_file_mapping = nullptr;
	}
#endif
}

std::shared_ptr<usb_device> usb_device_usio::make_instance(u32, const std::array<u8, 7>& location)
{
	return std::make_shared<usb_device_usio>(location);
}

u16 usb_device_usio::get_num_emu_devices()
{
	return 1;
}

void usb_device_usio::control_transfer(u8 bmRequestType, u8 bRequest, u16 wValue, u16 wIndex, u16 wLength, u32 buf_size, u8* buf, UsbTransfer* transfer)
{
	transfer->fake = true;

	// Control transfers are nearly instant
	//switch (bmRequestType)
	{
	//default:
		// Follow to default emulated handler
		usb_device_emulated::control_transfer(bmRequestType, bRequest, wValue, wIndex, wLength, buf_size, buf, transfer);
		//break;
	}
}

extern bool is_input_allowed();

void usb_device_usio::load_backup()
{
	usio_memory& memory = g_fxo->get<usio_memory>();
	memory.init();

	fs::file usio_backup_file;

	if (!usio_backup_file.open(usio_backup_path, fs::read))
	{
		usio_log.trace("Failed to load the USIO Backup file: %s", usio_backup_path);
		return;
	}

	const u64 file_size = memory.backup_memory.size();

	if (usio_backup_file.size() != file_size)
	{
		usio_log.trace("Invalid USIO Backup file detected: %s", usio_backup_path);
		return;
	}

	usio_backup_file.read(memory.backup_memory.data(), file_size);

	for (usz i = 0; i < memory.card_data.size(); i++)
	{
		if (fs::file usio_card_file;
			usio_card_file.open(fmt::format("%s/caches/usio_card_p%d.bin", rpcs3::utils::get_hdd1_dir(), i + 1), fs::read) &&
			usio_card_file.size() == memory.card_data[i].size())
		{
			usio_card_file.read(memory.card_data[i].data(), memory.card_data[i].size());
		}
	}
}

void usb_device_usio::save_backup()
{
	if (!is_used)
		return;

	fs::file usio_backup_file;

	if (!usio_backup_file.open(usio_backup_path, fs::create + fs::write + fs::lock))
	{
		usio_log.error("Failed to save the USIO Backup file: %s", usio_backup_path);
		return;
	}

	const u64 file_size = g_fxo->get<usio_memory>().backup_memory.size();

	usio_backup_file.write(g_fxo->get<usio_memory>().backup_memory.data(), file_size);
	usio_backup_file.trunc(file_size);
}

void usb_device_usio::translate_input_0x1080()
{
	std::vector<u8> input_buf(0x60);
	constexpr le_t<u16> c_hit = 0x1800;
	le_t<u16> digital_input = 0;
	auto& status = m_io_status[0];

	u32 tekno_control = 0;
	u8 coin_state = 0;
	u8 card_state[2] = {0, 0}; // Taiko has at most 2 players per cab; P1=byte31, P2=byte30

#ifdef _WIN32
	if (g_teknoparrot_view_ptr)
	{
		u8* tp = static_cast<u8*>(g_teknoparrot_view_ptr);
		tekno_control = *reinterpret_cast<u32*>(tp + 8);
		coin_state = tp[32];
		card_state[0] = tp[31]; // P1 card entry
		card_state[1] = tp[30]; // P2 card entry
	}
#elif defined(__ANDROID__)
	const auto input = get_android_arcade_input();
	tekno_control = static_cast<u32>(input.control);
	coin_state = input.coin;
	card_state[0] = input.card;
#endif

	if (tekno_control & 0x02) digital_input |= 0x200;  // P1 Start/Enter
	if (tekno_control & 0x40) digital_input |= 0x4000; // P1 Service
	if (tekno_control & 0x800) digital_input |= 0x2000; // P1 Up
	if (tekno_control & 0x2000) digital_input |= 0x1000; // P1 Down

	for (int player = 0; player < 2; player++)
	{
		const usz offset = player * 8ULL;
		if ((player == 0 && (tekno_control & 0x04)) || (player == 1 && (tekno_control & 0x10)))
			std::memcpy(input_buf.data() + 34 + offset, &c_hit, sizeof(u16)); // Center Left
		if ((player == 0 && (tekno_control & 0x20)) || (player == 1 && (tekno_control & 0x80)))
			std::memcpy(input_buf.data() + 36 + offset, &c_hit, sizeof(u16)); // Center Right
		if ((player == 0 && (tekno_control & 0x200)) || (player == 1 && (tekno_control & 0x4000)))
			std::memcpy(input_buf.data() + 32 + offset, &c_hit, sizeof(u16)); // Side Left
		if ((player == 0 && (tekno_control & 0x80000)) || (player == 1 && (tekno_control & 0x800000)))
			std::memcpy(input_buf.data() + 38 + offset, &c_hit, sizeof(u16)); // Side Right
	}

	bool test_pressed_now = (tekno_control & 0x01) != 0;
	if (test_pressed_now && !status.test_key_pressed)
		status.test_on = !status.test_on;
	status.test_key_pressed = test_pressed_now;

	if (status.test_on)
		digital_input |= 0x80;

#if defined(_WIN32) || defined(__ANDROID__)
	bool coin_pressed_now = (coin_state != 0);
	if (coin_pressed_now && !g_coin_pressed_prev)
	{
		m_io_status[0].coin_counter++;
		usio_log.trace("Taiko coin inserted, counter now: %d", m_io_status[0].coin_counter);
	}
	g_coin_pressed_prev = coin_pressed_now;

	// Card entry: reset every frame, then set per held TP card-tap byte (rising edge logged)
	for (usz i = 0; i < m_io_status.size(); i++)
		m_io_status[i].card_tapped = false;
	for (usz p = 0; p < 2; p++)
	{
		if (card_state[p] != 0)
		{
			tap_card(p);
			if (g_card_state_prev[p] == 0)
				usio_log.notice("Taiko card tapped (player %u)", p + 1);
		}
		g_card_state_prev[p] = card_state[p];
	}
#endif

	std::memcpy(input_buf.data(), &digital_input, sizeof(u16));
	std::memcpy(input_buf.data() + 16, &m_io_status[0].coin_counter, sizeof(u16));

	response = std::move(input_buf);
}

void usb_device_usio::translate_input_0x1000()
{
	std::vector<u8> input_buf(0x180);
	le_t<u64> digital_input[2]{};
	le_t<u16> digital_input_lm = 0;
	auto& status = m_io_status[0];

	u64 tekno_control = 0;
	u8 analog_data[7] = {0};
	u8 rotary_encoders[4] = {0};
	u8 coin_state = 0;
	u8 test_state = 0;
	u8 card_state[4] = {0, 0, 0, 0}; // P1=byte31, P2=byte30, P3=byte29, P4=byte28 (Tekken Pair Play, etc.)

#ifdef _WIN32
	if (g_teknoparrot_view_ptr)
	{
		u8* tp = static_cast<u8*>(g_teknoparrot_view_ptr);
		tekno_control      = *reinterpret_cast<u64*>(tp + 8);
		analog_data[0]     = tp[16];
		analog_data[1]     = tp[17];
		analog_data[2]     = tp[18];
		analog_data[3]     = tp[19];
		analog_data[4]     = tp[20]; // Dark Escape vital sensor P1
		analog_data[5]     = tp[21]; // Dark Escape vital sensor P2
		analog_data[6]     = tp[22];
		rotary_encoders[0] = tp[23];
		rotary_encoders[1] = tp[24];
		rotary_encoders[2] = tp[25];
		rotary_encoders[3] = tp[26];
		card_state[3]      = tp[28]; // P4 card entry
		card_state[2]      = tp[29]; // P3 card entry
		card_state[1]      = tp[30]; // P2 card entry
		card_state[0]      = tp[31]; // P1 card entry
		coin_state         = tp[32];
		test_state         = tp[33];
	}
#elif defined(__ANDROID__)
	const auto input = get_android_arcade_input();
	tekno_control = input.control;
	std::copy(input.analog.begin(), input.analog.end(), analog_data);
	std::copy(input.rotary.begin(), input.rotary.end(), rotary_encoders);
	coin_state = input.coin;
	test_state = input.test;
	card_state[0] = input.card;
#endif

	digital_input[0] = tekno_control;

	bool test_pressed_now = (test_state & 0x80) != 0;
	if (test_pressed_now && !status.test_key_pressed)
		status.test_on = !status.test_on;
	status.test_key_pressed = test_pressed_now;

	if (status.test_on)
	{
		digital_input[0] |= 0x80;
		digital_input_lm |= 0x1000;
	}

#if defined(_WIN32) || defined(__ANDROID__)
	bool coin_pressed_now = (coin_state != 0);
	if (coin_pressed_now && !g_coin_pressed_prev)
	{
		m_io_status[0].coin_counter++;
		usio_log.notice("Coin inserted, counter now: %d", m_io_status[0].coin_counter);
	}
	g_coin_pressed_prev = coin_pressed_now;

	// Card entry: reset every frame, then set per held TP card-tap byte (rising edge logged)
	for (usz i = 0; i < m_io_status.size(); i++)
		m_io_status[i].card_tapped = false;
	for (usz p = 0; p < 4; p++)
	{
		if (card_state[p] != 0)
		{
			tap_card(p);
			if (g_card_state_prev[p] == 0)
				usio_log.notice("Card tapped (player %u)", p + 1);
		}
		g_card_state_prev[p] = card_state[p];
	}
#endif

	for (usz i = 0; i < 2; i++)
	{
		std::memcpy(input_buf.data() + 0x80 + i * 0x80, &digital_input[i], sizeof(u64));
		std::memcpy(input_buf.data() + 0x80 + i * 0x80 + 0x10, &m_io_status[i].coin_counter, sizeof(u16));
	}

	for (int board = 0; board < 2; ++board)
	{
		for (int axis = 0; axis < static_cast<int>(sizeof(analog_data)); ++axis)
		{
			u16 val = static_cast<u16>(analog_data[axis]) * 257;
			auto* axis_ptr = reinterpret_cast<u16*>(input_buf.data() + 0xA0 + board * 0x80 + axis * 2);
			*axis_ptr = val;
		}
	}

	for (int board = 0; board < 2; ++board)
	{
		u8* enc = input_buf.data() + 0xB0 + board * 0x80;
		for (int i = 0; i < 4; ++i)
			enc[i] = rotary_encoders[i];
	}

	std::memcpy(input_buf.data(), &digital_input_lm, sizeof(u16));
	input_buf[2] = 0b00010000; // DIP switches

	response = std::move(input_buf);
}

void usb_device_usio::translate_input_taiko()
{
	std::lock_guard lock(pad::g_pad_mutex);
	const auto handler = pad::get_pad_thread();

	std::vector<u8> input_buf(0x60);
	constexpr le_t<u16> c_hit = 0x1800;
	le_t<u16> digital_input = 0;

	const auto translate_from_pad = [&](usz pad_number, usz player)
	{
		const usz offset = player * 8ULL;
		auto& status = m_io_status[0];

		if (const auto& pad = ::at32(handler->GetPads(), pad_number); pad->is_connected() && !pad->is_copilot() && is_input_allowed())
		{
			const auto& cfg = ::at32(g_cfg_usio.players, pad_number);
			cfg->handle_input(pad, false, [&](const auto& value, bool& /*abort*/)
			{
				switch (value.btn)
				{
				case usio_btn::test:
					if (player != 0) break;
					if (value.pressed && !status.test_key_pressed) // Solve the need to hold the Test key
						status.test_on = !status.test_on;
					status.test_key_pressed = value.pressed;
					break;
				case usio_btn::coin:
					if (player != 0) break;
					if (value.pressed && !status.coin_key_pressed) // Ensure only one coin is inserted each time the Coin key is pressed
						status.coin_counter++;
					status.coin_key_pressed = value.pressed;
					break;
				case usio_btn::service:
					if (player == 0 && value.pressed)
						digital_input |= 0x4000;
					break;
				case usio_btn::enter:
					if (player == 0 && value.pressed)
						digital_input |= 0x200;
					break;
				case usio_btn::up:
					if (player == 0 && value.pressed)
						digital_input |= 0x2000;
					break;
				case usio_btn::down:
					if (player == 0 && value.pressed)
						digital_input |= 0x1000;
					break;
				case usio_btn::taiko_hit_side_left:
					if (value.pressed)
						std::memcpy(input_buf.data() + 32 + offset, &c_hit, sizeof(u16));
					break;
				case usio_btn::taiko_hit_center_right:
					if (value.pressed)
						std::memcpy(input_buf.data() + 36 + offset, &c_hit, sizeof(u16));
					break;
				case usio_btn::taiko_hit_side_right:
					if (value.pressed)
						std::memcpy(input_buf.data() + 38 + offset, &c_hit, sizeof(u16));
					break;
				case usio_btn::taiko_hit_center_left:
					if (value.pressed)
						std::memcpy(input_buf.data() + 34 + offset, &c_hit, sizeof(u16));
					break;
				case usio_btn::card_tapping:
					if (value.pressed)
						tap_card(player);
					break;
				default:
					break;
				}
			});
		}

		if (player == 0 && status.test_on)
			digital_input |= 0x80;
	};

	for (usz i = 0; i < m_io_status.size(); i++)
		m_io_status[i].card_tapped = false;
	for (usz i = 0; i < g_cfg_usio.players.size(); i++)
		translate_from_pad(i, i);

	std::memcpy(input_buf.data(), &digital_input, sizeof(u16));
	std::memcpy(input_buf.data() + 16, &m_io_status[0].coin_counter, sizeof(u16));

	response = std::move(input_buf);
}

void usb_device_usio::translate_input_tekken()
{
	std::lock_guard lock(pad::g_pad_mutex);
	const auto handler = pad::get_pad_thread();

	std::vector<u8> input_buf(0x180);
	le_t<u64> digital_input[2]{};
	le_t<u16> digital_input_lm = 0;

	const auto translate_from_pad = [&](usz pad_number, usz player)
	{
		const usz shift = (player % 2) * 24ULL;
		auto& status = m_io_status[player / 2];
		auto& input = digital_input[player / 2];

		if (const auto& pad = ::at32(handler->GetPads(), pad_number); pad->is_connected() && !pad->is_copilot() && is_input_allowed())
		{
			const auto& cfg = ::at32(g_cfg_usio.players, pad_number);
			cfg->handle_input(pad, false, [&](const auto& value, bool& /*abort*/)
			{
				switch (value.btn)
				{
				case usio_btn::test:
					if (player % 2 != 0)
						break;
					if (value.pressed && !status.test_key_pressed) // Solve the need to hold the Test button
						status.test_on = !status.test_on;
					status.test_key_pressed = value.pressed;
					break;
				case usio_btn::coin:
					if (player % 2 != 0)
						break;
					if (value.pressed && !status.coin_key_pressed) // Ensure only one coin is inserted each time the Coin button is pressed
						status.coin_counter++;
					status.coin_key_pressed = value.pressed;
					break;
				case usio_btn::service:
					if (player % 2 == 0 && value.pressed)
						input |= 0x4000;
					break;
				case usio_btn::enter:
					if (value.pressed)
					{
						input |= 0x800000ULL << shift;
						if (player == 0)
							digital_input_lm |= 0x800;
					}
					break;
				case usio_btn::up:
					if (value.pressed)
					{
						input |= 0x200000ULL << shift;
						if (player == 0)
							digital_input_lm |= 0x200;
					}
					break;
				case usio_btn::down:
					if (value.pressed)
					{
						input |= 0x100000ULL << shift;
						if (player == 0)
							digital_input_lm |= 0x400;
					}
					break;
				case usio_btn::left:
					if (value.pressed)
					{
						input |= 0x80000ULL << shift;
						if (player == 0)
							digital_input_lm |= 0x2000;
					}
					break;
				case usio_btn::right:
					if (value.pressed)
					{
						input |= 0x40000ULL << shift;
						if (player == 0)
							digital_input_lm |= 0x4000;
					}
					break;
				case usio_btn::tekken_button1:
					if (value.pressed)
					{
						input |= 0x20000ULL << shift;
						if (player == 0)
							digital_input_lm |= 0x100;
					}
					break;
				case usio_btn::tekken_button2:
					if (value.pressed)
						input |= 0x10000ULL << shift;
					break;
				case usio_btn::tekken_button3:
					if (value.pressed)
						input |= 0x40000000ULL << shift;
					break;
				case usio_btn::tekken_button4:
					if (value.pressed)
						input |= 0x20000000ULL << shift;
					break;
				case usio_btn::tekken_button5:
					if (value.pressed)
						input |= 0x80000000ULL << shift;
					break;
				case usio_btn::card_tapping:
					if (value.pressed)
						tap_card(player);
					break;
				default:
					break;
				}
			});
		}

		if (player % 2 == 0 && status.test_on)
		{
			input |= 0x80;
			if (player == 0)
				digital_input_lm |= 0x1000;
		}
	};

	for (usz i = 0; i < m_io_status.size(); i++)
		m_io_status[i].card_tapped = false;
	for (usz i = 0; i < g_cfg_usio.players.size(); i++)
		translate_from_pad(i, i);

	for (usz i = 0; i < 2; i++)
	{
		std::memcpy(input_buf.data() - i * 0x80 + 0x100, &digital_input[i], sizeof(u64));
		std::memcpy(input_buf.data() - i * 0x80 + 0x100 + 0x10, &m_io_status[i].coin_counter, sizeof(u16));
	}

	std::memcpy(input_buf.data(), &digital_input_lm, sizeof(u16));

	input_buf[2] = 0b00010000; // DIP switches, 8 in total

	response = std::move(input_buf);
}

void usb_device_usio::emulate_card_reader(std::vector<u8>& buf, u16 reg)
{
	static std::array<std::vector<u8>, 2> pending_response = {};
	usz reader_index = 0;

	const auto calculate_checksum = [](bool check, std::vector<u8>& data) -> bool
	{
		if (data.size() < 0x06)
			return false;

		const usz data_end = data.size() - 2;
		u8 sum = data[3] + data[4];

		for (usz i = 5; i < data_end; i++)
			sum -= data[i];

		if (check)
			return *reinterpret_cast<le_t<u16>*>(&data[data_end]) == sum;

		*reinterpret_cast<le_t<u16>*>(&data[data_end]) = sum;
		return true;
	};

	switch (reg)
	{
	case 0x0080:
	case 0x0090:
	{
		reader_index = reg == 0x0080 ? 0 : 1;
		buf = {0x02, 0x03, 0x00, 0x00, 0xFF, 0x0F, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0x10, 0x00};
		*reinterpret_cast<le_t<u16>*>(buf.data() + 2) = ::narrow<u16>(pending_response[reader_index].size());
		break;
	}
	case 0x7000:
	case 0x7800:
	{
		reader_index = reg == 0x7000 ? 0 : 1;
		buf = std::move(pending_response[reader_index]);
		pending_response[reader_index].clear(); // Ensure its empty state after being moved
		break;
	}
	case 0x7400:
	case 0x7C00:
	{
		if (!calculate_checksum(true, buf))
			break;
		reader_index = reg == 0x7400 ? 0 : 1;
		const auto& status = ::at32(m_io_status, reader_index);
		const usz card_player = reader_index * 2 + status.card_index;
		const u8 payload_length = buf[3];
		const u8 command = buf[4];
		const u8* const payload = &buf[6];
		switch (command)
		{
		case 0xE8:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x0D, 0xF3, 0xD5, 0x07, 0xDC, 0xF4, 0x3F, 0x11, 0x4D, 0x85, 0x61, 0xF1, 0x26, 0x6A, 0x87, 0xC9, 0x00};
			break;
		}
		case 0xEE:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x0A, 0xF6, 0xD5, 0x07, 0xFF, 0x3F, 0x0E, 0xF1, 0xFF, 0x3F, 0x0E, 0xF1, 0xAA, 0x00};
			break;
		}
		case 0xF1:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x03, 0xFD, 0xD5, 0x41, 0x00, 0xEA, 0x00};
			break;
		}
		case 0xF2:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x33, 0xF8, 0x00};
			break;
		}
		case 0xF7:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x03, 0xFD, 0xD5, 0x4B, 0x00, 0xE0, 0x00};
			break;
		}
		case 0xFA:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x33, 0xF8, 0x00};
			break;
		}
		case 0xFB:
		{
			if (payload_length >= 5)
			{
				if (*reinterpret_cast<const le_t<u16>*>(&payload[0]) == 0x0140)
				{
					if (payload[3] < 4)
					{
						pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x13, 0xED, 0xD5, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xEA, 0x00};
						std::memcpy(pending_response[reader_index].data() + 8, g_fxo->get<usio_memory>().card_data[card_player].data() + payload[3] * 0x10, 0x10);
					}
					else
					{
						pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x03, 0xFD, 0xD5, 0x41, 0x13, 0xD7, 0x00};
					}
				}
				else
				{
					pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x03, 0xFD, 0xD5, 0x09, 0x00, 0x22, 0x00};
				}
			}
			break;
		}
		case 0xFC:
		{
			if (payload_length >= 2)
			{
				switch (payload[0])
				{
				case 0x52:
					pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x04, 0xFC, 0xD5, 0x53, 0x01, 0x00, 0xD7, 0x00};
					break;
				case 0x0E:
					pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x0F, 0x1C, 0x00};
					break;
				case 0x4A:
					if (status.card_tapped)
					{
						pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x0C, 0xF4, 0xD5, 0x4B, 0x01, 0x01, 0x00, 0x04, 0x08, 0x04, 0x00, 0x00, 0x00, 0x00, 0xCE, 0x00};
						std::memcpy(pending_response[reader_index].data() + 0x13, g_fxo->get<usio_memory>().card_data[card_player].data(), 4);
					}
					else
					{
						pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x03, 0xFD, 0xD5, 0x4B, 0x00, 0xE0, 0x00};
					}
					break;
				case 0x32:
					pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x33, 0xF8, 0x00};
					break;
				default:
					break;
				}
			}
			break;
		}
		case 0xFD:
		{
			if (payload_length >= 2)
			{
				switch (payload[0])
				{
				case 0x18:
					pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x19, 0x12, 0x00};
					break;
				case 0x12:
					pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD5, 0x13, 0x18, 0x00};
					break;
				default:
					break;
				}
			}
			break;
		}
		case 0xFE:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x05, 0xFB, 0xD5, 0x0D, 0x00, 0x06, 0x00, 0x18, 0x00};
			break;
		}
		case 0xFF:
		{
			pending_response[reader_index] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};
			break;
		}
		default:
		{
			usio_log.trace("Unhandled card reader command: 0x%02X", command);
			break;
		}
		}
		calculate_checksum(false, pending_response[reader_index]);
		break;
	}
	default:
		break;
	}
}

void usb_device_usio::tap_card(usz player)
{
	auto& status = ::at32(m_io_status, player / 2);
	status.card_tapped = true;
	status.card_index = player % 2;
}

void usb_device_usio::usio_write(u8 channel, u16 reg, std::vector<u8>& data)
{
	const auto get_u16 = [&](std::string_view usio_func) -> u16
	{
		if (data.size() != 2)
		{
			usio_log.error("data.size() is %d, expected 2 for get_u16 in %s", data.size(), usio_func);
		}
		return *reinterpret_cast<const le_t<u16>*>(data.data());
	};

	if (channel == 0)
	{
		switch (reg)
		{
		case 0x0002:
		{
			usio_log.trace("SetSystemError: 0x%04X", get_u16("SetSystemError"));
			break;
		}
		case 0x000A:
		{
			if (get_u16("ClearSram") == 0x6666)
			    usio_log.trace("ClearSram");
			break;
		}
		case 0x0028:
		{
			expansion_mode = get_u16("SetExpansionMode");
			usio_log.trace("SetExpansionMode: 0x%04X", expansion_mode);
			break;
		}
		case 0x0048:
		case 0x0058:
		case 0x0068:
		case 0x0078:
		{
			const usz hopper_idx = (reg - 0x48) / 0x10;
			if (hopper_idx < hoppers.size())
				hoppers[hopper_idx] = get_u16("SetHopperRequest");
			usio_log.trace("SetHopperRequest(Hopper: %d, Request: 0x%04X)", hopper_idx, get_u16("SetHopperRequest"));
			break;
		}
		case 0x004A:
		case 0x005A:
		case 0x006A:
		case 0x007A:
		{
			usio_log.trace("SetHopperLimit(Hopper: %d, Limit: 0x%04X)", (reg - 0x4A) / 0x10, get_u16("SetHopperLimit"));
			break;
		}
		case 0x0080:
		case 0x008D:
		case 0x0090:
		case 0x009D:
		case 0x7400:
		case 0x7C00:
		{
			emulate_card_reader(data, reg);
			break;
		}
		default:
		{
			usio_log.trace("Unhandled channel 0 register write(reg: 0x%04X, size: 0x%04X, data: %s)", reg, data.size(), fmt::buf_to_hexstring(data.data(), data.size()));
			break;
		}
		}
	}
	else if (channel >= 2)
	{
		const u8 page = channel - 2;
		usio_log.trace("Usio write of sram(page: 0x%02X, addr: 0x%04X, size: 0x%04X, data: %s)", page, reg, data.size(), fmt::buf_to_hexstring(data.data(), data.size()));
		auto& memory = g_fxo->get<usio_memory>().backup_memory;
		const usz addr_end = reg + data.size();
		if (data.size() > 0 && page < usio_memory::page_count && addr_end <= usio_memory::page_size)
			std::memcpy(&memory[usio_memory::page_size * page + reg], data.data(), data.size());
		else
			usio_log.error("Usio sram invalid write operation(page: 0x%02X, addr: 0x%04X, size: 0x%04X, data: %s)", page, reg, data.size(), fmt::buf_to_hexstring(data.data(), data.size()));
	}
	else
	{
		// Channel 1 is the endpoint for firmware update.
		// We are not using any firmware since this is emulation.
		usio_log.trace("Unsupported write operation(channel: 0x%02X, addr: 0x%04X, size: 0x%04X, data: %s)", channel, reg, data.size(), fmt::buf_to_hexstring(data.data(), data.size()));
	}
}

void usb_device_usio::usio_read(u8 channel, u16 reg, u16 size)
{
	if (channel == 0)
	{
		switch (reg)
		{
		case 0x0000:
		{
			// Razing Storm / Dark Escape 4D gun IO status (and connectivity check)
			// First U16 seems to be a timestamp of sort, [2]/[3] are error flags.
			response = {0x7E, 0xE4, 0x00, 0x00, 0x74, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E, 0x00, 0x7E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
			response.resize(0x100); // Razing storm needs the full 0x100 bytes for gun data etc.

			// IO board count
			response[0x22] = 0x01;

			// Expansion mode written by SetExpansionMode (reg 0x0028).
			// If not echoed back, razing storm keeps re-sending it every frame and tanks fps.
			response[0x28] = expansion_mode & 0xFF;
			response[0x29] = expansion_mode >> 8;

			// Mode of the gundrive board (razing storm sets it via hopper request 0x48).
			// 0x9000 = mode 5 (gun sensor check), 0x8000 = mode 4 (guns work ingame).
			response[0x40] = 0x00;
			response[0x41] = hoppers[0] >> 8;

			// Gun sensor states (P1 / P2). Razing storm reads these twice (one per player).
			response[0x44] = 0xFF;
			response[0x45] = 0xFF;
			response[0x46] = 0xFF;
			response[0x47] = 0xFF;

			response[0x54] = 0x00; // P1 gun status, needs to be 0 for the gun to work
			response[0x5C] = 0x00; // P2 gun status

			u8 analog_data[7] = {0}; // P1X, P1Y, P2X, P2Y, plus extras

#ifdef _WIN32
			if (g_teknoparrot_view_ptr)
			{
				analog_data[0] = static_cast<u8*>(g_teknoparrot_view_ptr)[16];
				analog_data[1] = static_cast<u8*>(g_teknoparrot_view_ptr)[17];
				analog_data[2] = static_cast<u8*>(g_teknoparrot_view_ptr)[18];
				analog_data[3] = static_cast<u8*>(g_teknoparrot_view_ptr)[19];
				analog_data[4] = static_cast<u8*>(g_teknoparrot_view_ptr)[20];
				analog_data[5] = static_cast<u8*>(g_teknoparrot_view_ptr)[21];
				analog_data[6] = static_cast<u8*>(g_teknoparrot_view_ptr)[22];
			}
#elif defined(__ANDROID__)
			const auto input = get_android_arcade_input();
			std::copy(input.analog.begin(), input.analog.end(), analog_data);
#endif

			// razing storm guns: scale 8-bit TP value to 16-bit (== * 257)
			const u16 p1_x = (static_cast<u16>(analog_data[0]) * 65535) / 255;
			const u16 p1_y = (static_cast<u16>(analog_data[1]) * 65535) / 255;
			const u16 p2_x = (static_cast<u16>(analog_data[2]) * 65535) / 255;
			const u16 p2_y = (static_cast<u16>(analog_data[3]) * 65535) / 255;

			response[0x50] = p1_x & 0xFF;
			response[0x51] = p1_x >> 8;
			response[0x52] = p1_y & 0xFF;
			response[0x53] = p1_y >> 8;

			response[0x58] = p2_x & 0xFF;
			response[0x59] = p2_x >> 8;
			response[0x5A] = p2_y & 0xFF;
			response[0x5B] = p2_y >> 8;
			break;
		}
		case 0x0080:
		case 0x0090:
		case 0x7000:
		case 0x7800:
		{
			emulate_card_reader(response, reg);
			break;
		}
		case 0x1000:
		{
			// Gets input for Tekken / Razing Storm / general games
#if defined(_WIN32) || defined(__ANDROID__)
			translate_input_0x1000();
#else
			translate_input_tekken();
#endif
			break;
		}
		case 0x1080:
		{
			// Gets input for Taiko
#if defined(_WIN32) || defined(__ANDROID__)
			translate_input_0x1080();
#else
			translate_input_taiko();
#endif
			break;
		}
		case 0x1400:
		{
			// LED feedback read (Taiko cabinet lights etc.) - acknowledge silently
			usio_log.trace("LED feedback read (reg 0x1400, size 0x%04X)", size);
			break;
		}
		case 0x1800:
		case 0x1880:
		{
			// Seems to contain a few extra bytes of info in addition to the firmware string
			// Firmware
			// "NBGI.;USIO01;Ver1.00;JPN,Multipurpose with PPG."
			constexpr std::array<u8, 0x180> info {0x4E, 0x42, 0x47, 0x49, 0x2E, 0x3B, 0x55, 0x53, 0x49, 0x4F, 0x30, 0x31, 0x3B, 0x56, 0x65, 0x72, 0x31, 0x2E, 0x30, 0x30, 0x3B, 0x4A, 0x50, 0x4E, 0x2C, 0x4D, 0x75, 0x6C, 0x74, 0x69, 0x70, 0x75, 0x72, 0x70, 0x6F, 0x73, 0x65, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x50, 0x50, 0x47, 0x2E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x4E, 0x42, 0x47, 0x49, 0x31, 0x3B, 0x55, 0x53, 0x49, 0x4F, 0x30, 0x31, 0x3B, 0x56, 0x65, 0x72, 0x31, 0x2E, 0x30, 0x30, 0x3B, 0x4A, 0x50, 0x4E, 0x2C, 0x4D, 0x75, 0x6C, 0x74, 0x69, 0x70, 0x75, 0x72, 0x70, 0x6F, 0x73, 0x65, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x50, 0x50, 0x47, 0x2E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x13, 0x00, 0x30, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x02, 0x00, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x03, 0x00, 0x75, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x4E, 0x42, 0x47, 0x49, 0x32, 0x3B, 0x55, 0x53, 0x49, 0x4F, 0x30, 0x31, 0x3B, 0x56, 0x65, 0x72, 0x31, 0x2E, 0x30, 0x30, 0x3B, 0x4A, 0x50, 0x4E, 0x2C, 0x4D, 0x75, 0x6C, 0x74, 0x69, 0x70, 0x75, 0x72, 0x70, 0x6F, 0x73, 0x65, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x50, 0x50, 0x47, 0x2E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x13, 0x00, 0x30, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x02, 0x00, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x03, 0x00, 0x75, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
			response = {info.begin() + (reg - 0x1800), info.end()};
			break;
		}
		default:
		{
			usio_log.trace("Unhandled channel 0 register read(reg: 0x%04X, size: 0x%04X)", reg, size);
			break;
		}
		}
	}
	else if (channel >= 2)
	{
		const u8 page = channel - 2;
		usio_log.trace("Usio read of sram(page: 0x%02X, addr: 0x%04X, size: 0x%04X)", page, reg, size);
		auto& memory = g_fxo->get<usio_memory>().backup_memory;
		const usz addr_end = reg + size;
		if (size > 0 && page < usio_memory::page_count && addr_end <= usio_memory::page_size)
			response.insert(response.end(), memory.begin() + (usio_memory::page_size * page + reg), memory.begin() + (usio_memory::page_size * page + addr_end));
		else
			usio_log.error("Usio sram invalid read operation(page: 0x%02X, addr: 0x%04X, size: 0x%04X)", page, reg, size);
	}
	else
	{
		// Channel 1 is the endpoint for firmware update.
		// We are not using any firmware since this is emulation.
		usio_log.trace("Unsupported read operation(channel: 0x%02X, addr: 0x%04X, size: 0x%04X)", channel, reg, size);
	}

	response.resize(size); // Always resize the response vector to the given size
}

void usb_device_usio::usio_init(u8 channel, u16 reg, u16 size)
{
	if (channel == 0)
	{
		switch (reg)
		{
		case 0x0008:
		{
			usio_log.trace("USIO Reset");
			break;
		}
		case 0x000A:
		{
			usio_log.trace("USIO ClearSram");
			g_fxo->get<usio_memory>().init();
			break;
		}
		default:
		{
			usio_log.trace("Unhandled channel 0 register init(reg: 0x%04X, size: 0x%04X)", reg, size);
			break;
		}
		}
	}
	else
	{
		usio_log.trace("Unsupported init operation(channel: 0x%02X, addr: 0x%04X, size: 0x%04X)", channel, reg, size);
	}
}

void usb_device_usio::interrupt_transfer(u32 buf_size, u8* buf, u32 endpoint, UsbTransfer* transfer)
{
	constexpr u8 USIO_COMMAND_WRITE = 0x90;
	constexpr u8 USIO_COMMAND_READ  = 0x10;
	constexpr u8 USIO_COMMAND_INIT  = 0xA0;

	static bool expecting_data = false;
	static std::vector<u8> usio_data;
	static u32 response_seek = 0;
	static u8 usio_channel   = 0;
	static u16 usio_register = 0;
	static u16 usio_length   = 0;

	transfer->fake            = true;
	transfer->expected_result = HC_CC_NOERR;
	// The latency varies per operation but it doesn't seem to matter for this device so let's go fast!
	transfer->expected_time = get_timestamp() + 1'000;

	is_used = true;

	switch (endpoint)
	{
	case 0x01:
	{
		// Write endpoint
		transfer->expected_count = buf_size;

		if (expecting_data)
		{
			usio_data.insert(usio_data.end(), buf, buf + buf_size);
			usio_length -= buf_size;

			if (usio_length == 0)
			{
				expecting_data = false;
				usio_write(usio_channel, usio_register, usio_data);
			}
			return;
		}

		// Commands
		if (buf_size != 6)
		{
			usio_log.error("Expected a command but buf_size != 6");
			return;
		}

		usio_channel  = buf[0] & 0xF;
		usio_register = *reinterpret_cast<le_t<u16>*>(&buf[2]);
		usio_length   = *reinterpret_cast<le_t<u16>*>(&buf[4]);

		if ((buf[0] & USIO_COMMAND_WRITE) == USIO_COMMAND_WRITE)
		{
			usio_log.trace("UsioWrite(Channel: 0x%02X, Register: 0x%04X, Length: 0x%04X)", usio_channel, usio_register, usio_length);
			if (((~(usio_register >> 8)) & 0xF0) != buf[1])
			{
				usio_log.error("Invalid UsioWrite command");
				return;
			}
			expecting_data = true;
			usio_data.clear();
		}
		else if ((buf[0] & USIO_COMMAND_READ) == USIO_COMMAND_READ)
		{
			usio_log.trace("UsioRead(Channel: 0x%02X, Register: 0x%04X, Length: 0x%04X)", usio_channel, usio_register, usio_length);
			response_seek = 0;
			response.clear();
			usio_read(usio_channel, usio_register, usio_length);
		}
		else if ((buf[0] & USIO_COMMAND_INIT) == USIO_COMMAND_INIT)
		{
			usio_log.trace("UsioInit(Channel: 0x%02X, Register: 0x%04X, Length: 0x%04X)", usio_channel, usio_register, usio_length);
			usio_init(usio_channel, usio_register, usio_length);
		}
		else
		{
			usio_log.error("Received an unexpected command: 0x%02X", buf[0]);
		}
		break;
	}
	case 0x82:
	{
		// Read endpoint
		const u32 size = std::min(buf_size, static_cast<u32>(response.size() - response_seek));
		memcpy(buf, response.data() + response_seek, size);
		response_seek += size;
		transfer->expected_count = size;
		break;
	}
	default:
		usio_log.error("Unhandled endpoint: 0x%x", endpoint);
		break;
	}
}
