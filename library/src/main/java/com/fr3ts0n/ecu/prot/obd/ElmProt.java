/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.ecu.ObdCodeItem;
import com.fr3ts0n.prot.TelegramListener;
import com.fr3ts0n.prot.TelegramWriter;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;


/**
 * Communication protocol to talk to a ELM327 OBD interface
 *
 * @author erwin
 */
public class ElmProt
	extends ObdProt
	implements TelegramListener, TelegramWriter, Runnable
{
	/**
	 * virtual OBD service for CAN monitoring
	 */
	public static final int OBD_SVC_CAN_MONITOR = 256;
	
	/**
	 * property name for ECU addresses
	 */
	public static final String PROP_ECU_ADDRESS = "ecuaddr";
	/**
	 * property name for protocol status
	 */
	public static final String PROP_STATUS = "status";
	
	/**
	 * CAN protocol handler
	 */
	public static final CanProtFord canProt = new CanProtFord();
	/**
	 * Adaptive timing handler
	 */
	public final AdaptiveTiming mAdaptiveTiming = new AdaptiveTiming();
	
	/**
	 * number of bytes expected from opponent
	 */
	private int charsExpected = 0;
	/**
	 * remember last command which was sent
	 */
	private char[] lastCommand;
	
	/**
	 * preferred ELM protocol to be selected
	 */
	static private PROT preferredProtocol = PROT.ELM_PROT_AUTO;
	
	/**
	 * list of identified ECU addresses
	 */
	final TreeSet<Integer> ecuAddresses = new TreeSet<Integer>();
	/**
	 * selected ECU address
	 */
	private int selectedEcuAddress = 0;
	/**
	 * custom ELM initialisation commands
	 */
	private final Vector<String> customInitCommands = new Vector<String>();
	/**
	 * last detected message counter ID
	 */
	private int lastMsgId = 0;

	/**
	 * ELM protocol ID's
	 */
	public enum PROT
	{
		ELM_PROT_AUTO("Automatic"),
		ELM_PROT_J1850PWM("SAE J1850 PWM (41.6 KBaud)"),
		ELM_PROT_J1850VPW("SAE J1850 VPW (10.4 KBaud)"),
		ELM_PROT_9141_2("ISO 9141-2 (5 Baud Init)"),
		ELM_PROT_14230_4("ISO 14230-4 KWP (5 Baud Init)"),
		ELM_PROT_14230_4F("ISO 14230-4 KWP (fast Init)"),
		ELM_PROT_15765_11_F("ISO 15765-4 CAN (11 Bit ID, 500 KBit)"),
		ELM_PROT_15765_29_F("ISO 15765-4 CAN (29 Bit ID, 500 KBit)"),
		ELM_PROT_15765_11_S("ISO 15765-4 CAN (11 Bit ID, 250 KBit)"),
		ELM_PROT_15765_29_S("ISO 15765-4 CAN (29 Bit ID, 250 KBit)"),
		ELM_PROT_J1939_29_S("SAE J1939 CAN (29 bit ID, 250* kbaud)"),
		ELM_PROT_USER1_CAN_11_S("User1 CAN (11* bit ID, 125* kbaud)"),
		ELM_PROT_USER2_CAN_11_S("User2 CAN (11* bit ID, 50* kbaud)");
		private final String description;
		
		PROT(String _description)
		{
			description = _description;
		}
		
		@Override
		public String toString()
		{
			return description;
		}
	}
	
	/**
	 * possible ELM responses and ID's
	 */
	enum RSP_ID
	{
		PROMPT(">"),
		OK("OK"),
		MODEL("ELM"),
		NODATA("NODATA"),
		SEARCH("SEARCHING"),
		ERROR("ERROR"),
		NOCONN("UNABLE"),
		NOCONN2("NABLETO"),
		CANERROR("CANERROR"),
		BUSBUSY("BUSBUSY"),
		BUSERROR("BUSERROR"),
		BUSINIERR("BUSINIT:ERR"),
		BUSINIERR2("BUSINIT:BUS"),
		BUSINIERR3("BUSINIT:...ERR"),
		FBERROR("FBERROR"),
		DATAERROR("DATAERROR"),
		BUFFERFULL("BUFFERFULL"),
		STOPPED("STOPPED"),
		RXERROR("<"),
		QMARK("?"),
		UNKNOWN("");
		private final String response;
		
		RSP_ID(String response)
		{
			this.response = response;
		}
		
		@Override
		public String toString()
		{
			return response;
		}
	}
	
	/**
	 * possible communication states
	 */
	public enum STAT
	{
		UNDEFINED("Undefined"),
		INITIALIZING("Initializing"),
		INITIALIZED("Initialized"),
		ECU_DETECT("ECU detect"),
		ECU_DETECTED("ECU detected"),
		CONNECTING("Connecting"),
		CONNECTED("Connected"),
		NODATA("No data"),
		STOPPED("Stopped"),
		DISCONNECTED("Disconnected"),
		BUSERROR("BUS error"),
		DATAERROR("DATA error"),
		RXERROR("RX error"),
		ERROR("Error");
		private final String elmState;
		
		STAT(String state)
		{
			elmState = state;
		}
		
		@Override
		public String toString()
		{
			return elmState;
		}
	}
	
	/**
	 * numeric IDs for commands
	 */
	public enum CMD
	{
		RESET("Z", 0, true), ///< reset adapter
		WARMSTART("WS", 0, true), ///< warm start
		PROTOCLOSE("PC", 0, true), ///< protocol close
		DEFAULTS("D", 0, true), ///< set all to defaults
		INFO("I", 0, true), ///< request adapter info
		LOWPOWER("LP", 0, true), ///< switch to low power mode
		ECHO("E", 1, true), ///< enable/disable echo
		SETLINEFEED("L", 1, true), ///< enable/disable line feeds
		SETSPACES("S", 1, true), ///< enable/disable spaces
		SETHEADER("H", 1, true), ///< enable/disable header response
		GETPROT("DP", 0, true), ///< get protocol
		SETPROT("SP", 1, true), ///< set protocol
		CANMONITOR("MA", 0, true), ///< monitor CAN messages
		SETPROTAUTO("SPA", 1, true), ///< set protocol auto
		ADAPTTIMING("AT", 1, true), ///< Set ELM internal adaptive timing (0-2)
		SETTIMEOUT("ST", 2, true), ///< set timeout (x*4ms)
		SETTXHDR("SH", 3, true), ///< set TX header
		SETCANRXFLT("CRA", 3, true), ///< set CAN RX filter
		CLRCANRXFLT("CRA", 0, true); ///< clear CAN RX filter
		
		static final String CMD_HEADER = "AT";
		private final String command;
		final int paramDigits;
		private final boolean disablingAllowed;
		private boolean enabled = true;
		
		CMD(String cmd, int numDigitsParameter, @SuppressWarnings("SameParameterValue") boolean allowAdaption)
		{
			command = cmd;
			paramDigits = numDigitsParameter;
			disablingAllowed = allowAdaption;
		}
		
		@Override
		public String toString()
		{
			return CMD_HEADER + command;
		}
		
		public boolean isEnabled()
		{
			return enabled;
		}
		
		void setEnabled(boolean enabled)
		{
			if (disablingAllowed)
			{
				this.enabled = enabled;
			}
			// log current state
			log.fine(String.format("ELM command '%s' -> %s",
				toString(),
				this.enabled ? "enabled" : "disabled"));
		}
		
		public boolean isDisablingAllowed()
		{
			return disablingAllowed;
		}
	}
	
	/**
	 * Adaptive timing mode
	 */
	public enum AdaptTimingMode
	{
		OFF,
		ELM_AT1,
		ELM_AT2,
		SOFTWARE
	}
	
	/**
	 * Adaptive ELM timing handler
	 * * optimizes ELM message timeout at runtime
	 */
	public class AdaptiveTiming
	{
		/**
		 * for ELM message timeout handling
		 */
		/**
		 * max. ELM Message Timeout [ms]
		 */
		private static final int ELM_TIMEOUT_MAX = 1000;
		/**
		 * default ELM message timeout
		 */
		private static final int ELM_TIMEOUT_DEFAULT = 200;
		/**
		 * Learning resolution of ELM Message Timeout [ms]
		 */
		private static final int ELM_TIMEOUT_RES = 4;
		/**
		 * minimum ELM timeout
		 */
		int ELM_TIMEOUT_MIN = 12;
		/**
		 * minimum ELM timeout (learned from vehicle)
		 */
		int ELM_TIMEOUT_LRN_LOW = 12;
		/**
		 * ELM message timeout: defaults to approx 200 [ms]
		 */
		int elmMsgTimeout = ELM_TIMEOUT_MAX;
		
		/**
		 * adaptive timing handling enabled?
		 */
		private AdaptTimingMode mode = AdaptTimingMode.OFF;
		
		public AdaptTimingMode getMode()
		{
			return mode;
		}
		
		public void setMode(AdaptTimingMode mode)
		{
			
			log.info(String.format("AdaptiveTiming mode: %s -> %s",
				this.mode.toString(),
				mode.toString()));
			this.mode = mode;
			// initialize with new mode
			initialize();
		}
		
		/**
		 * min. (configured) ELM Message Timeout
		 *
		 * @return minimum (configured) ELM timeout value [ms]
		 */
		public int getElmTimeoutMin()
		{
			return ELM_TIMEOUT_MIN;
		}
		
		/**
		 * Set min. (configured) ELM Message Timeout
		 *
		 * @param elmTimeoutMin minimum (configured) ELM timeout value [ms]
		 */
		public void setElmTimeoutMin(int elmTimeoutMin)
		{
			log.info(String.format("ELM min timeout: %d -> %d",
				ELM_TIMEOUT_MIN, elmTimeoutMin));
			ELM_TIMEOUT_MIN = elmTimeoutMin;
		}
		
		/**
		 * Initialize timing hadler
		 */
		void initialize()
		{
			if (mode == AdaptTimingMode.SOFTWARE)
			{
				// ... reset learned minimum timeout ...
				setElmTimeoutLrnLow(getElmTimeoutMin());
				// set default timeout
				setElmMsgTimeout(ELM_TIMEOUT_DEFAULT);
				// switch OFF ELM internal adaptive timing
				pushCommand(CMD.ADAPTTIMING, 0);
			}
			else
			{
				pushCommand(CMD.ADAPTTIMING, mode.ordinal());
			}
		}
		
		/**
		 * Adapt ELM message timeout
		 *
		 * @param increaseTimeout increase/decrease timeout
		 */
		void adapt(boolean increaseTimeout)
		{
			if (mode != AdaptTimingMode.SOFTWARE) { return; }
			if (increaseTimeout)
			{
				// increase OBD timeout since we may expect answers too fast
				if ((elmMsgTimeout + ELM_TIMEOUT_RES) < ELM_TIMEOUT_MAX)
				{
					// increase timeout, since we have just timed out
					setElmMsgTimeout(elmMsgTimeout + ELM_TIMEOUT_RES);
					// ... and limit MIN timeout for this session
					setElmTimeoutLrnLow(elmMsgTimeout);
				}
			}
			else
			{
				// reduce OBD timeout towards minimum limit
				if ((elmMsgTimeout - ELM_TIMEOUT_RES) >= getElmTimeoutLrnLow())
				{
					setElmMsgTimeout(elmMsgTimeout - ELM_TIMEOUT_RES);
				}
				
			}
		}
		
		/**
		 * LOW Learn value ELM Message Timeout
		 *
		 * @return currently learned timout value [ms]
		 */
		private int getElmTimeoutLrnLow()
		{
			return ELM_TIMEOUT_LRN_LOW;
		}
		
		/**
		 * set LOW Learn value ELM Message Timeout
		 *
		 * @param elmTimeoutLrnLow new learn value [ms]
		 */
		private void setElmTimeoutLrnLow(int elmTimeoutLrnLow)
		{
			log.info(String.format("ELM learn timeout: %d -> %d",
				ELM_TIMEOUT_LRN_LOW, elmTimeoutLrnLow));
			ELM_TIMEOUT_LRN_LOW = elmTimeoutLrnLow;
		}
		
		/**
		 * Set message timeout to ELM adapter to wait for valid response from vehicle
		 * If this timeout expires before a valid response is received from the
		 * vehicle, the ELM adapter will respond with "NO DATA"
		 *
		 * @param newTimeout desired timeout in milliseconds
		 */
		private void setElmMsgTimeout(int newTimeout)
		{
			if (newTimeout > 0 && newTimeout != elmMsgTimeout)
			{
				log.info("ELM Timeout: " + elmMsgTimeout + " -> " + newTimeout);
				// set the timeout variable
				elmMsgTimeout = newTimeout;
				// queue the new timeout message
				pushCommand(CMD.SETTIMEOUT, newTimeout / 4);
			}
		}
	}
	
	/**
	 * Creates a new instance of ElmProtocol
	 */
	public ElmProt()
	{
	}
	
	/**
	 * set preferred ELM protocol to be used
	 *
	 * @param protoIndex preferred ELM protocol index
	 */
	public static void setPreferredProtocol(int protoIndex)
	{
		preferredProtocol = PROT.values()[protoIndex];
		log.info("Preferred protocol: " + preferredProtocol);
	}
	
	/**
	 * set ECU address to be received
	 *
	 * @param ecuAddress ECU address to be filtered / 0 = clear address filter
	 */
	public void setEcuAddress(int ecuAddress)
	{
		log.info(String.format("Set ECU address: 0x%x", ecuAddress));
		selectedEcuAddress = ecuAddress;
		// ensure headers are off
		pushCommand(CMD.SETHEADER, 0);
		// set/clear RX filter (ATCRA is valid only for 11-bit/29-bit CAN IDs >= 0x100)
		if (selectedEcuAddress >= 0x100)
		{
			pushCommand(CMD.SETCANRXFLT, selectedEcuAddress);
		}
		else if (selectedEcuAddress > 0)
		{
			pushTelegram(String.format(Locale.US, "ATSR%02X", Integer.valueOf(selectedEcuAddress)));
		}
		else
		{
			pushCommand(CMD.CLRCANRXFLT, 0);
		}
	}
	
	/**
	 * disable a set of ELM commands ELM commands from preference
	 *
	 * @param disabledCmds set of ELM commands (ATxx strings) to be disabled
	 */
	public static void disableCommands(Set<String> disabledCmds)
	{
		for (CMD cmd : CMD.values())
		{
			cmd.setEnabled(disabledCmds == null
			               || !disabledCmds.contains(cmd.toString()));
		}
	}
	
	/**
	 * create ELM command string from command id and paramter
	 *
	 * @param cmdID ID of ELM command
	 * @param param parameter for ELM command (0 if not required)
	 * @return command char sequence or NULL if command disabled/invalid
	 */
	private String createCommand(CMD cmdID, int param)
	{
		String cmd = null;
		if (cmdID.isEnabled())
		{
			cmd = cmdID.toString();
			// if parameter is required and provided, add parameter to command
			if (cmdID.paramDigits > 0)
			{
				String fmtString = "%0".concat(String.valueOf(cmdID.paramDigits)).concat("X");
				cmd += String.format(fmtString, param);
			}
		}
		// return command String
		return cmd;
	}

	/**
	 * Send telegram form command queue
	 */
	private void sendQueuedTelegram()
	{
		if(!cmdStack.isEmpty())
		{
			// get last cmd stack element
			String cmd = cmdStack.pop();
			// send the telegram
			sendTelegram(cmd.toCharArray());
		}
	}

	/**
	 * send command to ELM adapter
	 *
	 * @param cmdID ID of ELM command
	 * @param param parameter for ELM command (0 if not required)
	 */
	public void sendCommand(CMD cmdID, int param)
	{
		pushCommand(cmdID, param);
		// now send command
		sendQueuedTelegram();
	}

	/**
	 * queue command to ELM command stack
	 *
	 * @param cmdID ID of ELM command
	 * @param param parameter for ELM command (0 if not required)
	 */
	private void pushCommand(CMD cmdID, int param)
	{
		String cmd = createCommand(cmdID, param);
		pushTelegram(cmd);
	}

	/**
	 * queue generic telegram buffer to command stack
	 * @param tgm telegram buffer
	 */
	private void pushTelegram(String tgm)
	{
		if (tgm != null && !tgm.isEmpty()) { cmdStack.push(tgm); }
	}
	
	@Override
	public void sendTelegram(char[] buffer)
	{
		log.fine(this.toString() + " TX:'" + String.valueOf(buffer) + "'");
		lastCommand = buffer;
		super.sendTelegram(buffer);
	}
	
	/**
	 * return numeric ID to given response
	 *
	 * @param response clear text response from ELM adapter
	 */
	private static RSP_ID getResponseId(String response)
	{
		RSP_ID result = RSP_ID.UNKNOWN;
		for (RSP_ID id : RSP_ID.values())
		{
			if (response.startsWith(id.toString()))
			{
				result = id;
				break;
			}
		}
		// return ID
		return (result);
	}
	
	/**
	 * send ELM adapter to sleep mode
	 */
	public void goToSleep()
	{
		sendCommand(CMD.LOWPOWER, 0);
	}
	
	/**
	 * Jaguar S-Type multi-module scan state (only used when JaguarStypeSupport.isEnabled())
	 */
	private volatile boolean customModuleBusActive = false;
	private volatile boolean moduleScanInProgress = false;
	private volatile boolean detectedJ1850Pwm = false;
	private volatile boolean demoCodesCleared = false;

	/**
	 * reset ELM adapter
	 */
	public void reset()
	{
		customModuleBusActive = false;
		moduleScanInProgress = false;
		detectedJ1850Pwm = false;
		demoCodesCleared = false;
		charsExpected = 0;
		responsePending = false;
		lastRxMsg = "";
		if (JaguarStypeSupport.isEnabled())
		{
			JaguarStypeSupport.resetSessionState();
		}
		if (KiaVengaSupport.isEnabled())
		{
			KiaVengaSupport.resetSessionState();
		}
		// reset all learned protocol data
		super.reset();
		// either RESET or INFO command needs to be enabled
		if (CMD.RESET.isEnabled())
		{ sendCommand(CMD.RESET, 0); }
		else
		{ sendCommand(CMD.INFO, 0); }
	}
	
	/**
	 * request addresses of all connected ECUs
	 * (received IDs are evaluated in @ref:handleDataMessage)
	 */
	private void queryEcus()
	{
		// set status to ECU detection
		setStatus(STAT.ECU_DETECT);
		
		// clear all identified ECU addresses
		ecuAddresses.clear();
		detectedJ1850Pwm = false;
		// clear selected ECU
		selectedEcuAddress = 0;
		// remember to disable headers again
		pushCommand(CMD.SETHEADER, 0);
		// request PIDs (from all devices)
		pushTelegram("0100");
		// enable headers
		pushCommand(CMD.SETHEADER, 1);
		// trigger sending of queued telegrams
		sendQueuedTelegram();
	}
	
	private void initialize()
	{
		// set status to INITIALIZING
		setStatus(STAT.INITIALIZING);
		
		// set to preferred protocol
		pushCommand(CMD.SETPROT, preferredProtocol.ordinal());
		
		// initialize adaptive timing handler
		mAdaptiveTiming.initialize();
		
		// speed up protocol by removing spaces and line feeds from output
		pushCommand(CMD.SETSPACES, 0);
		pushCommand(CMD.SETLINEFEED, 0);
		
		// immediate set echo off
		pushCommand(CMD.ECHO, 0);
	}
	
	/**
	 * Implementation of TelegramListener
	 */
	
	/**
	 * multiline response is pending, for responses w/o a length info
	 */
	private boolean responsePending = false;

	/**
	 * Get buffer as byte array from HEX string
	 *
	 * Parses each hex digit pair independently rather than via
	 * {@code new BigInteger(str, 16).toByteArray()}: BigInteger's
	 * two's-complement byte array silently drops leading zero bytes and
	 * silently prepends an extra 0x00 sign byte whenever the first hex pair's
	 * high bit is set, so its length does not reliably match {@code
	 * buffer.length / 2}. That misalignment previously threw off every index
	 * into the result in {@link #isValidIsoTelegram}.
	 *
	 * @param buffer hex string char buffer
	 * @return byte[] of buffer content, one byte per hex digit pair
	 */
	private byte[] fromHex(char[] buffer)
	{
		int len = buffer.length / 2;
		byte[] result = new byte[len];
		for (int i = 0; i < len; i++)
		{
			result[i] = (byte) Integer.parseInt(new String(buffer, i * 2, 2), 16);
		}
		return result;
	}

	/**
	 * Check if buffer contains a valid ISO telegram
	 * @param buffer Telegram buffer
	 * @return true if buffer contains valid ISO telegram, false otherwise
	 */
	private boolean isValidIsoTelegram(char[] buffer)
	{
		boolean result = false;
		// ISO Header=3bytes, footer=1byte > min 8 hex digits
		if(buffer.length > 8)
		{
			try {
				int i;
				int crc=0;
				byte[] binBuff = fromHex(buffer);
				int b0 = binBuff[0] & 0xFF;
				int b1 = binBuff[1] & 0xFF;
				// Headerless Jaguar / KIA extended (61/62...) and module DTC/session replies (50/53/54/58/59)
				// must never be sliced as raw ISO headers when last payload byte happens to match sum%256
				if (JaguarStypeSupport.isEnabled()
						&& (b0 == 0x62 || b0 == 0x53 || b0 == 0x58 || b0 == 0x59 || b0 == 0x50 || b0 == 0x54)
						&& b1 != 0x6B && b1 != 0xF1)
				{
					return false;
				}
				if (KiaVengaSupport.isEnabled()
						&& (b0 == 0x61 || b0 == 0x62 || b0 == 0x58 || b0 == 0x59 || b0 == 0x50 || b0 == 0x54)
						&& b1 != 0x6B && b1 != 0xF1)
				{
					return false;
				}
				for (i=0; i<binBuff.length-1; i++)
				{
					// treat each byte as unsigned - checksum is mod 256
					crc += binBuff[i] & 0xFF;
				}
				// check last byte matching accumulated CRC (both unsigned)
				result = ((crc & 0xFF) == (binBuff[i] & 0xFF));
			} catch (Exception e) {
				// Ignore exception - result=false
			}
		}
		return result;
	}

	private String extractIsoContent(char[] buffer)
	{
		/* Extract payload content out of ISO telegram
		 * ISO Tgm.: <TYPE><SENDER><DESTINATION><PayLoad...><CRC> */
		return String.copyValueOf(buffer,6,buffer.length-8);
	}

	/**
	 * handle incoming protocol telegram
	 *
	 * @param buffer - telegram buffer
	 * @return number of listeners notified
	 */
	@Override
	public synchronized int handleTelegram(char[] buffer)
	{
		int result = 0;
		String bufferStr = new String(buffer);
		log.fine(this.toString() + " RX:'" + bufferStr + "'");

		// empty result
		if (buffer.length == 0)
		{
			return result;
		}
		
		// if ths is echo of last command
		if (lastTxMsg.compareToIgnoreCase(bufferStr) == 0)
		{
			// ignore echoed command
			return result;
		}
		
		// log message reception as answer to last TX message
		log.fine("ELM rx:'" + bufferStr + "' (" + lastTxMsg + ")");
		
		// handle response
		switch (getResponseId(bufferStr))
		{
			case SEARCH:
				setStatus(status != STAT.ECU_DETECT ? STAT.CONNECTING : status);
				// NO break here
			case QMARK:
			case NODATA:
			case OK:
			case ERROR:
			case NOCONN:
			case NOCONN2:
			case CANERROR:
			case BUSERROR:
			case BUSINIERR:
			case BUSINIERR2:
			case BUSINIERR3:
			case BUSBUSY:
			case FBERROR:
			case DATAERROR:
			case BUFFERFULL:
			case RXERROR:
				// remember this as last received message
				// do NOT respond immediately
				lastRxMsg = bufferStr;
				break;

			case STOPPED:
				// remember this as last received message
				lastRxMsg = bufferStr;
				// re-queue last command
				pushTelegram(String.valueOf(lastCommand));
				break;

			case MODEL:
				initialize();
				break;
			
			// received a PROMPT, what was the last response?
			case PROMPT:
				String rxBeforePrompt = lastRxMsg;
				lastRxMsg = "";
				// check for last received message
				switch (getResponseId(rxBeforePrompt))
				{
					case NOCONN:
					case NOCONN2:
					case CANERROR:
					case BUSERROR:
					case BUSINIERR:
					case BUSINIERR2:
					case BUSINIERR3:
					case BUSBUSY:
					case FBERROR:
						responsePending = false;
						charsExpected = 0;
						if (handleOptionalProfileFailure())
						{
							break;
						}
						setStatus(STAT.DISCONNECTED);
						// re-queue last command
						pushTelegram(String.valueOf(lastCommand));
						// queue setting to preferred protocol
						pushCommand(CMD.SETPROT, preferredProtocol.ordinal());
						// Initialize adaptive timing
						mAdaptiveTiming.initialize();
						// immediately close current protocol
						sendCommand(CMD.PROTOCLOSE, 0);
						break;
					
					case DATAERROR:
						responsePending = false;
						charsExpected = 0;
						if (handleOptionalProfileFailure())
						{
							break;
						}
						setStatus(STAT.DATAERROR);
						sendCommand(CMD.WARMSTART, 0);
						break;
					
					case BUFFERFULL:
					case RXERROR:
						responsePending = false;
						charsExpected = 0;
						setStatus(STAT.RXERROR);
						sendCommand(CMD.WARMSTART, 0);
						break;
					
					case ERROR:
						responsePending = false;
						charsExpected = 0;
						if (handleOptionalProfileFailure())
						{
							break;
						}
						setStatus(STAT.ERROR);
						sendCommand(CMD.WARMSTART, 0);
						break;

					case NODATA:
						responsePending = false;
						charsExpected = 0;
						if (handleOptionalProfileFailure())
						{
							break;
						}
						if (service == OBD_SVC_READ_CODES
								|| service == OBD_SVC_PENDINGCODES
								|| service == OBD_SVC_PERMACODES)
						{
							// NODATA on 03/07/0A simply means no codes in that mode;
							// continue draining remaining code modes without resetting protocol
							if (cmdStack.isEmpty() && tCodes.isEmpty())
							{
								tCodes.put(0, new ObdCodeItem(0,
										Messages.getString("no.trouble.codes.set")));
							}
						}
						else
						{
							setStatus(STAT.NODATA);
							// re-queue next data item for PID-polling services
							if (service == OBD_SVC_DATA
									|| service == OBD_SVC_FREEZEFRAME
									|| service == OBD_SVC_VEH_INFO)
							{
								pushTelegram(
									String.valueOf(
										createTelegram(emptyBuffer, service, getNextSupportedPid()))
								);
							}
							// increase OBD timeout since we may expect answers too fast
							mAdaptiveTiming.adapt(true);
							// set to preferred protocol
							pushCommand(CMD.SETPROT, preferredProtocol.ordinal());
						}
						// NO break here since reaction is only queued
					
					case MODEL:
					case SEARCH:
					case STOPPED:
						// was already handled before prompt
					case QMARK:
						// last command stays ignored
					
					case OK:
					default:
						// if there is a pending data response, handle it now ...
						if (responsePending)
						{
							responsePending = false;
							charsExpected = 0;
							result = handleDataMessage(rxBeforePrompt);
						}
						
						// queued commands will be sent first
						if (!cmdStack.isEmpty())
						{
							sendQueuedTelegram();
						}
						else
						{
							if (moduleScanInProgress)
							{
								moduleScanInProgress = false;
								setStatus(STAT.CONNECTED);
								if (service == OBD_SVC_READ_CODES && tCodes.isEmpty())
								{
									tCodes.put(0, new ObdCodeItem(0,
											Messages.getString("no.trouble.codes.set")));
								}
							}
							// all queued commands are sent -> we are done initializing
							if (status == STAT.INITIALIZING)
							{
								// set status to initialized
								setStatus(STAT.INITIALIZED);
								// push custom init commands
								cmdStack.addAll(customInitCommands);
								// initiate query of connected ECUs
								queryEcus();
								break;
							}
							
							// all queued commands are sent -> we are done detecting ECUs
							setStatus(status == STAT.ECU_DETECT ? STAT.ECU_DETECTED : status);
							
							switch (service)
							{
								case OBD_SVC_VEH_INFO:
									// if all pid's have been read once ...
									if (pidsWrapped)
									{
										// ... terminate service loop
										break;
									}
									// no break here ...
								case OBD_SVC_DATA:
								case OBD_SVC_FREEZEFRAME:
								{
									if (service == OBD_SVC_DATA
											&& JaguarStypeSupport.isEnabled()
											&& hasSupportedPids())
									{
										String mode22Cmd = JaguarStypeSupport.nextInterleavedMode22Command(
												System.currentTimeMillis(), true);
										if (mode22Cmd != null)
										{
											List<String> pollSeq = JaguarStypeSupport.buildMode22PollSequence(
													mode22Cmd,
													ecuAddresses,
													selectedEcuAddress,
													detectedJ1850Pwm || preferredProtocol == PROT.ELM_PROT_J1850PWM);
											if (pollSeq.size() == 1)
											{
												sendTelegram(pollSeq.get(0).toCharArray());
											}
											else if (!pollSeq.isEmpty())
											{
												pushOrderedCommands(pollSeq);
												sendQueuedTelegram();
											}
											break;
										}
									}
									else if (service == OBD_SVC_DATA
											&& KiaVengaSupport.isEnabled()
											&& hasSupportedPids())
									{
										List<String> kiaSeq = KiaVengaSupport.nextInterleavedPollSequence(
												System.currentTimeMillis(),
												true,
												selectedEcuAddress);
										if (!kiaSeq.isEmpty())
										{
											pushOrderedCommands(kiaSeq);
											sendQueuedTelegram();
											break;
										}
									}
									// otherwise the next PID will be requested
									writeTelegram(emptyBuffer, service, getNextSupportedPid());
									// reduce OBD timeout towards minimum limit
									mAdaptiveTiming.adapt(false);
								}
								break;
								
								case OBD_SVC_NONE:
								default:
									// do nothing
							}
						}
				}
				break;
			
			// handle data response
			default:
				// if we are still initializing check for address entries
				switch (status)
				{
					case ECU_DETECT:
					{
						// start of 0100 response is end of address
						// Search "4100" first because SAE J1850 PWM headers begin with "416B..."
						int adrEnd = bufferStr.indexOf("4100");
						if (adrEnd < 0)
						{
							adrEnd = bufferStr.indexOf("41");
						}
						// if not a service response, check for possible NRC
						if(adrEnd < 0)
						{
							adrEnd = bufferStr.indexOf("7F01");
						}
						int adrStart = bufferStr.lastIndexOf(".") + 1;
						int dataEnd = bufferStr.length();

						if (adrEnd > adrStart)
						{
							int adrLen = adrEnd - adrStart;
							if ((adrLen % 2) != 0)
							{
								/*
								 * odd address length
								 * -> CAN address length = 3 digits + 2 digits frame type
								 */
								adrLen = 3;
							}
							else
							{
								if (adrLen == 6)
								{
									/*
									 * 6 char (3 byte) prefix -> ISO9141 / J1850 address <FF><RR><SS>
									 *     FF - Frame type (0x41 = SAE J1850 PWM, 0x48 = ISO 9141-2)
									 *     RR - receiver address
									 *     SS - sender address
									 */
									String hdr = bufferStr.substring(adrStart, adrEnd).toUpperCase(Locale.US);
									if (hdr.startsWith("416B") || hdr.startsWith("C4"))
									{
										detectedJ1850Pwm = true;
									}
									adrLen = 2;
									adrStart = adrEnd - adrLen;
									// subtract ISO/J1850 checksum from payload
									dataEnd -= 2;
								}
								else if (adrLen == 10)
								{
									/*
									 * 29 bit CAN address
									 * <CP><AABBCC><FT>
									 *     CP = CAN priority (5 relevant bits)
									 *     AABBCC = Address (24 Bit)
									 *     FT = Frame type
									 * -> CAN address length = 32 bits / 8 digits <CP><AABBCC>
									 */
									adrLen = 8;
									adrStart = 0;
								}
							}
							// extract address
							String address = bufferStr.substring(adrStart, adrStart + adrLen);
							
							log.fine(String.format("Found ECU address: 0x%s", address));
							// and add to list of addresses
							ecuAddresses.add(Integer.valueOf(address, 16));
							// extract / process payload data
							bufferStr = bufferStr.substring(adrEnd, dataEnd);
						}
						return handleDataMessage(bufferStr);
					}
				}
				
				// we are connected ...
				setStatus(STAT.CONNECTED);

				if( isValidIsoTelegram(buffer) )
					// extract ISO content
					bufferStr = extractIsoContent(buffer);

				// ELM clone verbose message (starting with '+')
				if(buffer[0] == '+')
				{
					// ignore message
					return (result);
				}
				
				// is this a length identifier?
				if (buffer[0] == '0' && buffer.length == 3)
				{
					// then remember the length to be expected
					charsExpected = Integer.valueOf(bufferStr, 16) * 2;
					lastRxMsg = "";
					return (result);
				}
				
				// is this a multi-line response
				int idx = bufferStr.indexOf(':');

				// .. or a ISO multi line response with format SVC PID MSGID DATA...
				if((idx < 0) && (buffer.length == 14))
				{
					final int[] dfcServices = {OBD_SVC_READ_CODES, OBD_SVC_PENDINGCODES, OBD_SVC_PERMACODES};
					int msgService = Integer.valueOf(bufferStr.substring(0, 2), 0x10) & ~0x40;
					// If response to current service and no DFC response ...
					if(msgService == getService()
					   && Arrays.binarySearch(dfcServices, msgService) < 0)
					{
						// Use header on 1st response, cut from continuation messages
						int msgId = Integer.valueOf(bufferStr.substring(4,6),0x10);
						if(msgId <= 1)
						{
							// 1st response line
							idx = 0;
							lastMsgId = msgId;
						}
						else if (msgId == (lastMsgId + 1))
						{
							// continuation line
							idx = 5;
							lastMsgId = msgId;
						}
						else
						{
							// NOT a ISO multi line message
							idx = -1;
							// cut additional (padding) byte
							bufferStr = bufferStr.substring(0,buffer.length-2);
						}
					}
				}

				if (idx >= 0)
				{
					if(idx == 0)
					{
						// initial ISO multiline message
						lastRxMsg = bufferStr;
						charsExpected = 0;
					}
					else if (buffer[0] == '0'
							&& !((charsExpected > 0 || responsePending) && !lastRxMsg.isEmpty()))
					{
						// first line of a multiline message
						lastRxMsg = bufferStr.substring(idx + 1);
					}
					else
					{
						// continuation lines
						// concat response without line counter
						lastRxMsg += bufferStr.substring(idx + 1);
					}

					/* no length known, set marker for pending response
					   response will be finished on reception of prompt */
					responsePending = (charsExpected == 0);
				}
				else
				{
					// otherwise use this as last received message
					lastRxMsg = bufferStr;
					charsExpected = 0;
					responsePending = false;
				}
				

				// if we haven't received complete result yet, then wait for the rest
				if (lastRxMsg.length() < charsExpected)
				{
					return (result);
				}
				
				// Trim a multiline response to expected length (cut off padding)
				if((charsExpected > 0) && (lastRxMsg.length() > charsExpected))
				{
					lastRxMsg = lastRxMsg.substring(0, charsExpected);
				}
				
				// if response is finished, handle it
				if (!responsePending)
				{
					result = handleDataMessage(lastRxMsg);
					charsExpected = 0;
				}
		}
		return (result);
	}
	
	/**
	 * forward data message for further handling
	 *
	 * @param lastRxMsg received message to be forwarded
	 * @return number of bytes processed
	 */
	private int handleDataMessage(String lastRxMsg)
	{
		int result = 0;
		
		// otherwise process response
		switch (service)
		{
			case OBD_SVC_CAN_MONITOR:
				result = canProt.handleTelegram(lastRxMsg.toCharArray());
				break;
			
			default:
				// Let the OBD protocol handle the telegram
				result = super.handleTelegram(lastRxMsg.toCharArray());
		}
		return result;
	}

	private boolean handleOptionalProfileFailure()
	{
		if (JaguarStypeSupport.isEnabled()
				&& (moduleScanInProgress || JaguarStypeSupport.isOptionalJaguarCommand(lastTxMsg)))
		{
			JaguarStypeSupport.onOptionalCommandFailed(0, 0, lastTxMsg);
			proceedAfterOptionalFailure();
			return true;
		}
		if (KiaVengaSupport.isEnabled()
				&& (moduleScanInProgress || KiaVengaSupport.isOptionalKiaCommand(lastTxMsg)))
		{
			KiaVengaSupport.onOptionalCommandFailed(0, 0, lastTxMsg);
			proceedAfterOptionalFailure();
			return true;
		}
		return false;
	}

	/**
	 * Continue draining queued commands or resume polling after an optional Jaguar/KIA command
	 * (Mode 21/22 PID probe or multi-module DTC request) returns NODATA / CANERROR / NOCONN.
	 */
	private void proceedAfterOptionalFailure()
	{
		if (!cmdStack.isEmpty())
		{
			sendQueuedTelegram();
			return;
		}
		if (moduleScanInProgress)
		{
			moduleScanInProgress = false;
			setStatus(STAT.CONNECTED);
			if (service == OBD_SVC_READ_CODES && tCodes.isEmpty())
			{
				tCodes.put(0, new ObdCodeItem(0, Messages.getString("no.trouble.codes.set")));
			}
			return;
		}
		if (service == OBD_SVC_DATA || service == OBD_SVC_FREEZEFRAME)
		{
			setStatus(STAT.CONNECTED);
			writeTelegram(emptyBuffer, service, getNextSupportedPid());
		}
	}

	/**
	 * Push an ordered list of ELM327 commands onto LIFO cmdStack so they execute in forward order.
	 */
	private void pushOrderedCommands(List<String> orderedCmds)
	{
		if (orderedCmds == null || orderedCmds.isEmpty())
		{
			return;
		}
		for (int i = orderedCmds.size() - 1; i >= 0; i--)
		{
			pushTelegram(orderedCmds.get(i));
		}
	}

	private List<String> buildStandardBusRestoreCommands()
	{
		List<String> cmds = new ArrayList<String>();
		cmds.add("ATPC");
		cmds.add("ATD");
		cmds.add("ATE0");
		cmds.add("ATL0");
		cmds.add("ATS0");
		cmds.add("ATH0");
		String setProtCmd = createCommand(CMD.SETPROT, preferredProtocol.ordinal());
		if (setProtCmd != null)
		{
			cmds.add(setProtCmd);
		}
		if (selectedEcuAddress >= 0x100)
		{
			String craCmd = createCommand(CMD.SETCANRXFLT, selectedEcuAddress);
			if (craCmd != null)
			{
				cmds.add(craCmd);
			}
		}
		else if (selectedEcuAddress > 0)
		{
			cmds.add(String.format(Locale.US, "ATSR%02X", Integer.valueOf(selectedEcuAddress)));
		}
		return cmds;
	}

	/**
	 * Scan DTCs on a specific 2002 Jaguar S-Type module (only active when Jaguar S-Type is selected).
	 */
	public synchronized void scanJaguarModuleDtcs(JaguarStypeSupport.DiagModule module)
	{
		if (!JaguarStypeSupport.isEnabled())
		{
			return;
		}
		JaguarStypeSupport.DiagModule target =
				(module != null) ? module : JaguarStypeSupport.DiagModule.ENGINE_OBD;
		JaguarStypeSupport.setActiveModule(target);
		demoCodesCleared = false;
		this.service = OBD_SVC_READ_CODES;
		cmdStack.clear();
		prepareModuleScanReset();

		if (runDemo)
		{
			simulateJaguarDemoDtcs(target);
			return;
		}

		if (target == JaguarStypeSupport.DiagModule.ENGINE_OBD)
		{
			moduleScanInProgress = false;
			List<String> seq = new ArrayList<String>();
			if (customModuleBusActive)
			{
				seq.addAll(buildStandardBusRestoreCommands());
				customModuleBusActive = false;
			}
			seq.add("0101");
			seq.add(String.format(Locale.US, "%02X", Integer.valueOf(OBD_SVC_READ_CODES)));
			seq.add(String.format(Locale.US, "%02X", Integer.valueOf(OBD_SVC_PENDINGCODES)));
			pushOrderedCommands(seq);
			sendQueuedTelegram();
		}
		else
		{
			List<String> cmds = JaguarStypeSupport.buildModuleReadCommands(
					target, JaguarStypeSupport.getProfile());
			if (!cmds.isEmpty())
			{
				customModuleBusActive = true;
				moduleScanInProgress = true;
				pushOrderedCommands(cmds);
				sendQueuedTelegram();
			}
		}
	}

	/**
	 * Clear DTCs on a specific 2002 Jaguar S-Type module and re-scan to confirm.
	 */
	public synchronized void clearJaguarModuleDtcs(JaguarStypeSupport.DiagModule module)
	{
		if (!JaguarStypeSupport.isEnabled())
		{
			return;
		}
		JaguarStypeSupport.DiagModule target =
				(module != null) ? module : JaguarStypeSupport.DiagModule.ENGINE_OBD;
		JaguarStypeSupport.setActiveModule(target);
		cmdStack.clear();
		prepareModuleScanReset();

		if (runDemo)
		{
			demoCodesCleared = true;
			tCodes.put(0, new ObdCodeItem(0, Messages.getString("no.trouble.codes.set")));
			return;
		}

		if (target == JaguarStypeSupport.DiagModule.ENGINE_OBD)
		{
			moduleScanInProgress = false;
			List<String> seq = new ArrayList<String>();
			if (customModuleBusActive)
			{
				seq.addAll(buildStandardBusRestoreCommands());
				customModuleBusActive = false;
			}
			seq.add("04");
			seq.add("0101");
			seq.add("03");
			seq.add("07");
			this.service = OBD_SVC_READ_CODES;
			pushOrderedCommands(seq);
			sendQueuedTelegram();
		}
		else
		{
			List<String> seq = new ArrayList<String>();
			seq.addAll(JaguarStypeSupport.buildModuleClearCommands(
					target, JaguarStypeSupport.getProfile()));
			seq.addAll(JaguarStypeSupport.buildModuleReadCommands(
					target, JaguarStypeSupport.getProfile()));
			if (!seq.isEmpty())
			{
				this.service = OBD_SVC_READ_CODES;
				customModuleBusActive = true;
				moduleScanInProgress = true;
				pushOrderedCommands(seq);
				sendQueuedTelegram();
			}
		}
	}

	/**
	 * Scan DTCs on a specific KIA Venga ECU module (only active when KIA Venga profile is selected).
	 */
	public synchronized void scanKiaModuleDtcs(KiaVengaSupport.DiagModule module)
	{
		if (!KiaVengaSupport.isEnabled())
		{
			return;
		}
		KiaVengaSupport.DiagModule target =
				(module != null) ? module : KiaVengaSupport.DiagModule.ENGINE_OBD;
		KiaVengaSupport.setActiveModule(target);
		demoCodesCleared = false;
		this.service = OBD_SVC_READ_CODES;
		cmdStack.clear();
		prepareModuleScanReset();

		if (runDemo)
		{
			simulateKiaDemoDtcs(target);
			return;
		}

		if (target == KiaVengaSupport.DiagModule.ENGINE_OBD)
		{
			moduleScanInProgress = false;
			List<String> seq = new ArrayList<String>();
			if (customModuleBusActive)
			{
				seq.addAll(buildStandardBusRestoreCommands());
				customModuleBusActive = false;
			}
			seq.add("0101");
			seq.add(String.format(Locale.US, "%02X", Integer.valueOf(OBD_SVC_READ_CODES)));
			seq.add(String.format(Locale.US, "%02X", Integer.valueOf(OBD_SVC_PENDINGCODES)));
			seq.add(String.format(Locale.US, "%02X", Integer.valueOf(OBD_SVC_PERMACODES)));
			pushOrderedCommands(seq);
			sendQueuedTelegram();
		}
		else
		{
			List<String> cmds = KiaVengaSupport.buildModuleReadCommands(target);
			if (!cmds.isEmpty())
			{
				customModuleBusActive = true;
				moduleScanInProgress = true;
				pushOrderedCommands(cmds);
				sendQueuedTelegram();
			}
		}
	}

	/**
	 * Clear DTCs on a specific KIA Venga ECU module and re-scan to confirm.
	 */
	public synchronized void clearKiaModuleDtcs(KiaVengaSupport.DiagModule module)
	{
		if (!KiaVengaSupport.isEnabled())
		{
			return;
		}
		KiaVengaSupport.DiagModule target =
				(module != null) ? module : KiaVengaSupport.DiagModule.ENGINE_OBD;
		KiaVengaSupport.setActiveModule(target);
		cmdStack.clear();
		prepareModuleScanReset();

		if (runDemo)
		{
			demoCodesCleared = true;
			tCodes.put(0, new ObdCodeItem(0, Messages.getString("no.trouble.codes.set")));
			return;
		}

		if (target == KiaVengaSupport.DiagModule.ENGINE_OBD)
		{
			moduleScanInProgress = false;
			List<String> seq = new ArrayList<String>();
			if (customModuleBusActive)
			{
				seq.addAll(buildStandardBusRestoreCommands());
				customModuleBusActive = false;
			}
			seq.add("04");
			seq.add("0101");
			seq.add("03");
			seq.add("07");
			seq.add("0A");
			this.service = OBD_SVC_READ_CODES;
			pushOrderedCommands(seq);
			sendQueuedTelegram();
		}
		else
		{
			List<String> seq = new ArrayList<String>();
			seq.addAll(KiaVengaSupport.buildModuleClearCommands(target));
			seq.addAll(KiaVengaSupport.buildModuleReadCommands(target));
			if (!seq.isEmpty())
			{
				this.service = OBD_SVC_READ_CODES;
				customModuleBusActive = true;
				moduleScanInProgress = true;
				pushOrderedCommands(seq);
				sendQueuedTelegram();
			}
		}
	}

	private void simulateKiaDemoDtcs(KiaVengaSupport.DiagModule module)
	{
		boolean isDiesel = (KiaVengaSupport.getProfile() == KiaVengaSupport.Profile.VENGA_DIESEL);
		switch (module)
		{
			case ECM:
				if (isDiesel)
				{
					// P1186 (CRDi Fuel Rail Pressure Too Low), P1405 (DPF Regen Incomplete)
					super.handleTelegram("5902FF1186000814050004".toCharArray());
				}
				else
				{
					// P1312 (Gamma Ignition Coil 2), P1690 (SMARTRA Immobilizer No Response)
					super.handleTelegram("5902FF1312000816900004".toCharArray());
				}
				break;
			case TCM:
				// P1706 (Inhibitor Switch PRND), P0711 (ATF Temp Sensor Range)
				super.handleTelegram("5902FF1706000807110004".toCharArray());
				break;
			case ABS_ESC:
				// C1513 (5513: Brake Light Switch), C1200 (5200: FL Wheel Speed Sensor)
				super.handleTelegram("5902FF5513000852000004".toCharArray());
				break;
			case MDPS:
				// C1290 (5290: MDPS Torque Sensor), C1261 (5261: SAS Not Calibrated)
				super.handleTelegram("5902FF5290000852610004".toCharArray());
				break;
			case SRS_ACU:
				// B1346 (9346: Driver Airbag Clock Spring), B1378 (9378: Side Airbag Connector)
				super.handleTelegram("5902FF9346000893780004".toCharArray());
				break;
			case BCM:
				// B1978 (9978: Start/Stop Button), U1111 (D111: IBS Battery Sensor LIN)
				super.handleTelegram("5902FF99780008D1110004".toCharArray());
				break;
			case CLU:
				// U0155 (C155: Cluster CAN Communication)
				super.handleTelegram("5902FFC1550004".toCharArray());
				break;
			case FATC:
				// B2406 (A406: Air-Mix Flap Motor), B1242 (9242: Evaporator Temp Sensor Open)
				super.handleTelegram("5902FFA406000892420004".toCharArray());
				break;
			case TPMS:
				// C1121 (5121: FL TPMS Battery Low), C1312 (5312: FL TPMS RF Signal Loss)
				super.handleTelegram("5902FF5121000853120004".toCharArray());
				break;
			case ENGINE_OBD:
			default:
				super.handleTelegram("410183000000".toCharArray());
				if (isDiesel)
				{
					super.handleTelegram("430211861405".toCharArray());
					super.handleTelegram("47011324".toCharArray());
				}
				else
				{
					super.handleTelegram("430213120420".toCharArray());
					super.handleTelegram("47011693".toCharArray());
				}
				break;
		}
	}

	private void simulateKiaDemoExtended(int tick)
	{
		// 1. ECM 7E0 2101 (35 bytes): V=14.2V (209), Idle=750 (75), MAP=36.5 kPa, EOT=91.5 C
		char[] ecm01 = new char[35];
		ecm01[5] = (char) 209;
		ecm01[9] = (char) 75;
		int mapRaw = 9344 + (tick % 40) * 64; // LE u16 / 256 -> ~36.5..46.5 kPa
		ecm01[16] = (char) (mapRaw & 0xFF);
		ecm01[17] = (char) ((mapRaw >> 8) & 0xFF);
		ecm01[34] = (char) (184 + (tick % 8)); // *0.75 - 48 -> 90.0..95.25 C
		KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x01, ecm01, PidPvs);

		if (KiaVengaSupport.getProfile() != KiaVengaSupport.Profile.VENGA_DIESEL)
		{
			// Gamma CVVT 2117: Target ~ 14.0 deg (s8=28), Actual ~ 13.8 deg (s16_le=1766)
			char[] cvvt = new char[7];
			int deg2 = 20 + (tick % 24);
			cvvt[4] = (char) (deg2 & 0xFF);
			int actLe = deg2 * 64 - 16;
			cvvt[5] = (char) (actLe & 0xFF);
			cvvt[6] = (char) ((actLe >> 8) & 0xFF);
			KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x17, cvvt, PidPvs);

			// Knock Retard 2116 & Misfires 2108
			char[] knock = new char[16];
			knock[13] = (char) ((tick % 15 == 0) ? 2 : 0); // occasional 1.5 deg on cyl 2
			KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x16, knock, PidPvs);

			char[] mf = new char[24];
			mf[18] = (char) (demoCodesCleared ? 0 : 3); // cyl 2 misfire count
			KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x08, mf, PidPvs);
		}

		if (KiaVengaSupport.getProfile() != KiaVengaSupport.Profile.VENGA_PETROL)
		{
			// CRDi 2103: EGT ~ 315 C, Dist since DPF regen ~ 184.5 km (184500 m)
			char[] crdi03 = new char[57];
			crdi03[46] = (char) (45 + (tick % 15));
			int distM = 184500 + tick * 12;
			crdi03[53] = (char) ((distM >> 24) & 0xFF);
			crdi03[54] = (char) ((distM >> 16) & 0xFF);
			crdi03[55] = (char) ((distM >> 8) & 0xFF);
			crdi03[56] = (char) (distM & 0xFF);
			KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x03, crdi03, PidPvs);

			char[] crdi06 = new char[26];
			crdi06[25] = (char) ((tick % 50 > 40) ? 1 : 0);
			KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x06, crdi06, PidPvs);

			char[] dpf22 = new char[13];
			dpf22[12] = (char) (28 + (tick % 10)); // ~10.9..14.7 g soot
			KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_EXT_DATA, 0xED94, dpf22, PidPvs);
		}

		// IBS Battery 2127: SOC 86%, SOH 94%, Current +4.8 A ((204.8/0.008)=25600=0x6400)
		char[] ibs = new char[14];
		ibs[6] = 0x64;
		ibs[7] = (char) ((tick % 20) * 8);
		ibs[12] = (char) 86;
		ibs[13] = (char) 94;
		KiaVengaSupport.onExtendedResponse("7E0", OBD_SVC_KWP_DATA, 0x27, ibs, PidPvs);

		// TCM 7E1 21A0: ATF ~ 78 C (118), Slip ~ 18 RPM (2120=0x0848), Gear 1..6, Turbine/Output RPM
		char[] tcm = new char[24];
		int turbRaw = (1800 + (tick % 40) * 20) * 4;
		int outRaw = (1760 + (tick % 40) * 20) * 4;
		tcm[9] = (char) ((turbRaw >> 8) & 0xFF);
		tcm[10] = (char) (turbRaw & 0xFF);
		tcm[11] = (char) ((outRaw >> 8) & 0xFF);
		tcm[12] = (char) (outRaw & 0xFF);
		tcm[13] = (char) (116 + (tick % 6));
		int slipRaw = (512 + 12 + (tick % 24)) * 4;
		tcm[16] = (char) ((slipRaw >> 8) & 0xFF);
		tcm[17] = (char) (slipRaw & 0xFF);
		tcm[21] = (char) (1 + ((tick / 8) % 6));
		KiaVengaSupport.onExtendedResponse("7E1", OBD_SVC_KWP_DATA, 0xA0, tcm, PidPvs);

		// ABS 7D1 2101: Wheel speeds ~ 58 km/h
		char[] abs = new char[15];
		int spd = 42 + (tick % 30);
		abs[11] = (char) spd;
		abs[12] = (char) spd;
		abs[13] = (char) spd;
		abs[14] = (char) spd;
		KiaVengaSupport.onExtendedResponse("7D1", OBD_SVC_KWP_DATA, 0x01, abs, PidPvs);

		// MDPS 7D4 2101 (SAS angle) & 220101 (Motor current)
		char[] mdps01 = new char[5];
		short sasRaw = (short) (((tick % 30) - 15) * 6); // -9.0 .. +8.4 deg
		mdps01[3] = (char) ((sasRaw >> 8) & 0xFF);
		mdps01[4] = (char) (sasRaw & 0xFF);
		KiaVengaSupport.onExtendedResponse("7D4", OBD_SVC_KWP_DATA, 0x01, mdps01, PidPvs);

		char[] mdps22 = new char[12];
		mdps22[11] = (char) (3 + (tick % 5));
		KiaVengaSupport.onExtendedResponse("7D4", OBD_SVC_EXT_DATA, 0x0101, mdps22, PidPvs);

		// Cluster 7C6 22B002: Fuel 31.5 L (63), V 14.22 V (182), Odometer 128450 km (0x01F5C2)
		char[] clu = new char[12];
		clu[4] = (char) 63;
		clu[5] = (char) 182;
		int odoKm = 128450;
		clu[6] = (char) ((odoKm >> 16) & 0xFF);
		clu[7] = (char) ((odoKm >> 8) & 0xFF);
		clu[8] = (char) (odoKm & 0xFF);
		KiaVengaSupport.onExtendedResponse("7C6", OBD_SVC_EXT_DATA, 0xB002, clu, PidPvs);

		// TPMS 7D6 2106: ~2.25 bar (163), ~26 C (76)
		char[] tpms = new char[16];
		tpms[0] = (char) 163; tpms[1] = (char) 76;
		tpms[4] = (char) 164; tpms[5] = (char) 76;
		tpms[8] = (char) 161; tpms[9] = (char) 74;
		tpms[12] = (char) 162; tpms[13] = (char) 75;
		KiaVengaSupport.onExtendedResponse("7D6", OBD_SVC_KWP_DATA, 0x06, tpms, PidPvs);
	}

	private void simulateJaguarDemoDtcs(JaguarStypeSupport.DiagModule module)
	{
		switch (module)
		{
			case PCM_SCP:
			case ECM_CAN:
				// P1111 (System Pass), P1646 (Linear O2 B1S1), P1384 (VVT B1), P1532 (IMT B2)
				super.handleTelegram("5804111160164660138460153220".toCharArray());
				break;
			case TCM:
				// P1780 (Trans Control Switch J-Gate), P0730 (Incorrect Gear Ratio)
				super.handleTelegram("5802178060073060".toCharArray());
				break;
			case ABS_DSC:
				// C1095 (5095: ABS Hydraulic Pump Motor), C1145 (5145: RF Wheel Speed Sensor)
				super.handleTelegram("5802509560514520".toCharArray());
				break;
			case CCM:
				// B1265 (9265: Cool Air Bypass Servo), U2520 (E520: CAN Timeout from ICCM)
				super.handleTelegram("539265E520".toCharArray());
				break;
			case IC_PATS:
				// B1600 (9600: PATS Transponder Not Received), U1262 (D262: SCP J1850 Bus Fault)
				super.handleTelegram("5802960060D26220".toCharArray());
				break;
			case RCM:
				// B1921 (9921: Airbag Diagnostic Monitor Ground), B1318 (9318: Battery Voltage Low)
				super.handleTelegram("5802992160931820".toCharArray());
				break;
			case EPB:
				// C1093 (5093: EPB Switch Circuit), C1D15 (5D15: Park Brake Latch Switch)
				super.handleTelegram("58025093605D1520".toCharArray());
				break;
			case ENGINE_OBD:
			default:
				// Standard OBD + Jaguar P1111, P1646, P0171, P0174
				super.handleTelegram("410183000000".toCharArray());
				super.handleTelegram("4303111116460171".toCharArray());
				super.handleTelegram("470201741384".toCharArray());
				break;
		}
	}

	private void simulateJaguarDemoMode22(int tick)
	{
		// TFT ~ 82.5 C (180.5 F -> raw = 1444 = 0x05A4)
		int tftRaw = 1400 + (tick % 80);
		super.handleTelegram(String.format(Locale.US, "621674%04X", Integer.valueOf(tftRaw)).toCharArray());
		// TOT (ZF 6HP26 1-byte CAN formula: raw - 50 C -> 83..92 C)
		int totByte = 133 + (tick % 10);
		super.handleTelegram(String.format(Locale.US, "6211BD%02X", Integer.valueOf(totByte)).toCharArray());
		// EOT ~ 96.0 C (raw = 13600 = 0x3520)
		int eotRaw = 13300 + (tick % 120) * 4;
		super.handleTelegram(String.format(Locale.US, "621310%04X", Integer.valueOf(eotRaw)).toCharArray());
		// TC Slip ~ 12..48 RPM (raw = slip * 4)
		int slipRaw = (8 + (tick % 36)) * 4;
		super.handleTelegram(String.format(Locale.US, "6211B8%04X", Integer.valueOf(slipRaw)).toCharArray());
		// Current Gear 1..5 (raw = gear * 2)
		int gearRaw = (1 + ((tick / 10) % 5)) * 2;
		super.handleTelegram(String.format(Locale.US, "6211B3%02X", Integer.valueOf(gearRaw)).toCharArray());
		// Cooling Fan Speed & Duty
		int fanByte = 60 + (tick % 40);
		super.handleTelegram(String.format(Locale.US, "62162B%02X", Integer.valueOf(fanByte)).toCharArray());
		super.handleTelegram(String.format(Locale.US, "62163F%02X", Integer.valueOf(fanByte)).toCharArray());
		// VVT Bank 1 & Bank 2 Duty + Angle + Error
		int vvtDuty1 = 9800 + (tick % 60) * 80;
		int vvtDuty2 = 9600 + (tick % 60) * 80;
		int vvtAngle = (int) ((12 + (tick % 18)) * 12.8f);
		super.handleTelegram(String.format(Locale.US, "6216CF%04X", Integer.valueOf(vvtDuty1)).toCharArray());
		super.handleTelegram(String.format(Locale.US, "62091E%04X", Integer.valueOf(vvtDuty2)).toCharArray());
		super.handleTelegram(String.format(Locale.US, "6216CD%04X", Integer.valueOf(vvtAngle)).toCharArray());
		super.handleTelegram("6216CE0004".toCharArray());
		// IMT Intake Valve (toggles at higher simulated RPM) & VREF ~ 5.01 V (0xFFC0)
		super.handleTelegram(((tick % 40) > 25 ? "62110310" : "62110300").toCharArray());
		super.handleTelegram("621155FFC0".toCharArray());
		// Supercharger IAT2 ~ 48 C (0x58) & Module DTC count
		super.handleTelegram(String.format(Locale.US, "62123B%02X", Integer.valueOf(84 + (tick % 10))).toCharArray());
		super.handleTelegram((demoCodesCleared ? "62020000" : "62020003").toCharArray());
	}
	
	// switch to exit the demo thread
	public static boolean runDemo;
	
	/**
	 * run threaded loop to simulate incoming telegrams
	 */
	public void run()
	{
		int value = 0;
		int demoTick = 0;
		Integer pid;
		runDemo = true;
		demoCodesCleared = false;
		
		log.info("ELM DEMO thread started");
		while (runDemo)
		{
			try
			{
				handleTelegram(RSP_ID.MODEL.toString().toCharArray());
				// test case for issue AndrOBD/#61
				handleTelegram("+CONNECTING<<94:65:2D:9E:DF:B5".toCharArray());
				
				setStatus(STAT.ECU_DETECT);
				handleTelegram("SEARCHING...".toCharArray());
				if (JaguarStypeSupport.isEnabled())
				{
					handleTelegram("486B104100BF9FA8919B".toCharArray());
					handleTelegram("7E8064100000000".toCharArray());
					handleTelegram("7E9074100000000".toCharArray());
					handleTelegram("768074100000000".toCharArray());
					handleTelegram("7CC074100000000".toCharArray());
				}
				else if (KiaVengaSupport.isEnabled())
				{
					handleTelegram("7E8064100BE3FA813".toCharArray());
					handleTelegram("7E906410080000000".toCharArray());
					handleTelegram("7D906410000000000".toCharArray());
					handleTelegram("7DC06410000000000".toCharArray());
					handleTelegram("7CE06410000000000".toCharArray());
					handleTelegram("7DE06410000000000".toCharArray());
				}
				else
				{
					handleTelegram("7EA074100000000".toCharArray());
					handleTelegram("486B104100BF9FA8919B".toCharArray());
					handleTelegram("...486B104100BF9FA8919B".toCharArray());
					handleTelegram("7E8064100000000".toCharArray());
					handleTelegram("7E9074100000000".toCharArray());
					handleTelegram("7EA074100000000".toCharArray());
					// test case for issue AndrOBD/#60
					handleTelegram("18DAF110064100BE3EB811".toCharArray());
				}
				setStatus(STAT.ECU_DETECTED);
				
				while (runDemo)
				{
					switch (service)
					{
						// read any kinds of trouble codes
						case OBD_SVC_READ_CODES:
						case OBD_SVC_PENDINGCODES:
						case OBD_SVC_PERMACODES:
							if (JaguarStypeSupport.isEnabled())
							{
								if (!demoCodesCleared && tCodes.isEmpty())
								{
									simulateJaguarDemoDtcs(JaguarStypeSupport.getActiveModule());
								}
								Thread.sleep(500);
								break;
							}
							if (KiaVengaSupport.isEnabled())
							{
								if (!demoCodesCleared && tCodes.isEmpty())
								{
									simulateKiaDemoDtcs(KiaVengaSupport.getActiveModule());
								}
								Thread.sleep(500);
								break;
							}
							// simulate 12 TCs set as multy line response
							// number of codes = 12 + MIL ON
							handleTelegram("41018C000000".toCharArray());
							// send codes as multy line response
							handleTelegram("014".toCharArray());
							handleTelegram("0:438920B920BD".toCharArray());
							handleTelegram("1:C002242A246E02".toCharArray());
							handleTelegram("2:36010101162453".toCharArray());

							// simulate 12 TCs set as subsequent single line responses
							// send codes as subsequent single line responses
							handleTelegram("478420BA20BC".toCharArray());
							handleTelegram("4784C004242B".toCharArray());

							// test pattern from AndrOBD/#78
							// 4 DFCs, 10 byte multiline response , padded
							handleTelegram("00A".toCharArray());
							handleTelegram("0:4A8401180122".toCharArray());
							handleTelegram("1:02232610000000".toCharArray());

							Thread.sleep(500);
							break;
						
						// otherwise send data ...
						case OBD_SVC_DATA:
						case OBD_SVC_FREEZEFRAME:
							pid = getNextSupportedPid();
							if (pid != 0)
							{
								value++;
								value &= 0xFF;
								// format new data message and handle it as new reception
								handleTelegram(String.format(
									service == OBD_SVC_DATA ? "4%X%02X%02X%02X%02X%02X"
									                        : "4%X%02X00%02X%02X%02X%02X",
									service, pid, value, value, value, value).toCharArray());
								if (service == OBD_SVC_DATA
										&& JaguarStypeSupport.isEnabled()
										&& (demoTick % 8 == 0))
								{
									simulateJaguarDemoMode22(demoTick / 8);
								}
								else if (service == OBD_SVC_DATA
										&& KiaVengaSupport.isEnabled()
										&& (demoTick % 8 == 0))
								{
									simulateKiaDemoExtended(demoTick / 8);
								}
								demoTick++;
							}
							else
							{
								// simulate "ALL PIDs supported"
								int i;
								for (i = 0; i < 0xE0; i += 0x20)
								{
									handleTelegram(String.format(
										service == OBD_SVC_DATA ? "4%X%02XFFFFFFFF"
										                        : "4%X%02X00FFFFFFFF", service, i)
										               .toCharArray());
								}
								handleTelegram(String.format(
									service == OBD_SVC_DATA ? "4%X%02XFFFFFFFE"
									                        : "4%X%02X00FFFFFFFE", service, i)
									               .toCharArray());
							}
							break;
						
						case OBD_SVC_VEH_INFO:
							pid = getNextSupportedPid();
							if (pid == 0)
							{
								// simulate "ALL pids supported"
								handleTelegram("490054000000".toCharArray());
							}
							
							if (JaguarStypeSupport.isEnabled())
							{
								// send Jaguar S-Type VIN "SAJAC01P62FM45255"
								handleTelegram("014".toCharArray());
								handleTelegram("1:49020153414A41".toCharArray());
								handleTelegram("2:433031503632".toCharArray());
								handleTelegram("3:464D3435323535".toCharArray());
							}
							else if (KiaVengaSupport.isEnabled())
							{
								// send European KIA Venga (Nošovice) VIN "TMAEH812AAJ045123"
								handleTelegram("014".toCharArray());
								handleTelegram("1:490201544D4145".toCharArray());
								handleTelegram("2:483831324141".toCharArray());
								handleTelegram("3:4A303435313233".toCharArray());
							}
							else
							{
								// send VIN "0123456789ABCDEFG"
								handleTelegram("014".toCharArray());
								handleTelegram("1:49020130313233".toCharArray());
								handleTelegram("2:343536373839".toCharArray());
								handleTelegram("3:41424344454647".toCharArray());
							}
							
							// send 2 CAL-IDs "GSPA..." without length id
							handleTelegram("0:490402475350".toCharArray());
							handleTelegram("1:412D3132333435".toCharArray());
							handleTelegram("2:363738393030".toCharArray());
							handleTelegram("3:30313233".toCharArray());
							handleTelegram("4:343536373839".toCharArray());
							handleTelegram("5:414243444546".toCharArray());
							
							// CAL-ID 01234567
							handleTelegram("490601234567".toCharArray());
							break;
						
						case OBD_SVC_CTRL_MODE:
							handleTelegram("4800C0000000".toCharArray());
							break;

						case OBD_SVC_NONE:
							// just keep quiet until soneone requests something
							break;
						
						default:
							// respond "service not supported"
							handleTelegram(String.format("7F%02X11", service).toCharArray());
							Thread.sleep(500);
							break;
						
					}
					Thread.sleep(50);
				}
			}
			catch (Exception ex)
			{
				log.severe(ex.getLocalizedMessage());
			}
		}
		log.info("ELM DEMO thread finished");
	}
	
	/**
	 * set custom initialisation commands
	 *
	 * @param commands custom initialisation commands
	 */
	public void setCustomInitCommands(String[] commands)
	{
		List<String> cmds = Arrays.asList(commands);
		// reverse list, since all commands are pushed rather than queued
		Collections.reverse(cmds);
		// clear list
		customInitCommands.clear();
		// add all entries
		customInitCommands.addAll(cmds);
	}
	
	/**
	 * Setter for property service.
	 *
	 * @param service    New value of property service.
	 * @param clearLists clear data list for this service
	 */
	@Override
	public void setService(int service, boolean clearLists)
	{
		// log the change in service
		if (service != this.service)
		{
			log.info("OBD Service: " + this.service + "->" + service);
			this.service = service;
			moduleScanInProgress = false;

			// If we previously targeted a custom Jaguar module (CCM / ABS / TCM / etc.)
			// and are now switching back to standard OBD services (Live Data, Freeze Frame, Veh Info),
			// restore default ELM327 headers & protocol before starting the service!
			if (customModuleBusActive && service != OBD_SVC_NONE && service != OBD_SVC_CAN_MONITOR)
			{
				boolean stayOnCustomModule = (service == OBD_SVC_READ_CODES)
						&& ((JaguarStypeSupport.isEnabled()
						     && JaguarStypeSupport.getActiveModule() != JaguarStypeSupport.DiagModule.ENGINE_OBD)
						    || (KiaVengaSupport.isEnabled()
						        && KiaVengaSupport.getActiveModule() != KiaVengaSupport.DiagModule.ENGINE_OBD));
				if (!stayOnCustomModule)
				{
					customModuleBusActive = false;
					cmdStack.clear();
					if (clearLists)
					{
						clearDataLists(service);
					}
					List<String> seq = new ArrayList<String>(buildStandardBusRestoreCommands());
					if (service == OBD_SVC_DATA
							|| service == OBD_SVC_FREEZEFRAME
							|| service == OBD_SVC_VEH_INFO
							|| service == OBD_SVC_CTRL_MODE)
					{
						seq.add(String.format(Locale.US, "%02X00", Integer.valueOf(service)));
					}
					else if (service == OBD_SVC_READ_CODES)
					{
						seq.add("0101");
						seq.add("03");
						seq.add("07");
					}
					else if (service == OBD_SVC_CLEAR_CODES)
					{
						seq.add("04");
					}
					pushOrderedCommands(seq);
					sendQueuedTelegram();
					return;
				}
			}

			// If Jaguar or KIA mode is active and a non-engine module is currently selected on Fault Codes,
			// delegate OBD_SVC_READ_CODES / OBD_SVC_CLEAR_CODES to the active vehicle module scanner.
			if (clearLists
					&& JaguarStypeSupport.isEnabled()
					&& JaguarStypeSupport.getActiveModule() != JaguarStypeSupport.DiagModule.ENGINE_OBD)
			{
				if (service == OBD_SVC_READ_CODES)
				{
					scanJaguarModuleDtcs(JaguarStypeSupport.getActiveModule());
					return;
				}
				if (service == OBD_SVC_CLEAR_CODES)
				{
					clearJaguarModuleDtcs(JaguarStypeSupport.getActiveModule());
					return;
				}
			}
			if (clearLists
					&& KiaVengaSupport.isEnabled()
					&& KiaVengaSupport.getActiveModule() != KiaVengaSupport.DiagModule.ENGINE_OBD)
			{
				if (service == OBD_SVC_READ_CODES)
				{
					scanKiaModuleDtcs(KiaVengaSupport.getActiveModule());
					return;
				}
				if (service == OBD_SVC_CLEAR_CODES)
				{
					clearKiaModuleDtcs(KiaVengaSupport.getActiveModule());
					return;
				}
			}
			
			// send corresponding command(s)
			switch (service)
			{
				case OBD_SVC_CAN_MONITOR:
					sendCommand(CMD.CANMONITOR, 0);
					break;
				
				default:
					super.setService(service, clearLists);
			}
		}
	}
	
	/**
	 * set OBD service - compatibility function
	 *
	 * @param service New value of property service.
	 */
	public void setService(int service)
	{
		setService(service, true);
	}
	
	/**
	 * Holds value of property status.
	 */
	private STAT status = STAT.UNDEFINED;
	
	/**
	 * Getter for property status.
	 *
	 * @return Value of property status.
	 */
	public STAT getStatus()
	{
		return this.status;
	}
	
	/**
	 * Setter for property status.
	 *
	 * @param status New value of property status.
	 */
    void setStatus(STAT status)
	{
		STAT oldStatus = this.status;
		this.status = status;
		if (status != oldStatus)
		{
			log.info("Status change: " + oldStatus + "->" + status);
			// ECUs detected -> send identified ECU addresses
			if (status == STAT.ECU_DETECTED)
			{
				firePropertyChange(
					new PropertyChangeEvent(this, PROP_ECU_ADDRESS, null, ecuAddresses));
			}
			
			// now fire regular status change
			firePropertyChange(new PropertyChangeEvent(this, PROP_STATUS, oldStatus, status));
		}
	}
}
