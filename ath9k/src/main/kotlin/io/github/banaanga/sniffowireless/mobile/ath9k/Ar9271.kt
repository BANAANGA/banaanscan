package io.github.banaanga.sniffowireless.mobile.ath9k

import io.github.banaanga.sniffowireless.mobile.ath9k.R.*

/*
 * The AR9271's MAC and baseband, set up from the host as Linux's ath9k_hw does it (hw.c, ar5008_phy.c, ar9002_phy.c,
 * ar9002_calib.c, calib.c, eeprom_4k.c, ani.c; ISC licence, see NOTICE), reduced to what this chip needs to receive:
 * the 2.4 GHz band, one chain, monitor mode. Transmit power tables, transmit queues, the key cache, ANI and the LED are
 * left out: the driver never transmits. Comments name the Linux function each part comes from.
 */

/** A 2.4 GHz channel. [ht] as mac80211 sets it: HT20 when the channel was set with a width, plain otherwise. */
data class Channel(val freqMhz: Int, val ht: Boolean = false) {
    init { require(freqMhz in 2412..2484) { "The AR9271 only covers 2.4 GHz" } }
    val number get() = if (freqMhz == 2484) 14 else (freqMhz - 2407) / 5
}

class Ar9271(private var r: Regs, private val log: (String) -> Unit = {}) {
    var macVersion = 0
        private set
    var macRev = 0
        private set
    /** The 4K EEPROM (ar5416_eeprom_4k) as bytes. */
    var eeprom = ByteArray(0)
        private set
    var macAddress = ByteArray(6)
        private set

    private var resetPowerOn = false
    private var htcResetInit = true
    private var chipFullSleep = false
    private var awake = false
    private var curChan: Channel? = null
    private var clockRate = ATH9K_CLOCK_RATE_CCK
    private val rxChainmask = 1
    private val txChainmask = 1
    private val staId1Defaults = AR_STA_ID1_CRPT_MIC_ENABLE.v or AR_STA_ID1_MCAST_KSRCH.v
    private val slotTime = 9
    private var txGainHigh = false
    private val bssidMask = byteArrayOf(-1, -1, -1, -1, -1, -1)
    /** Diagnostics: how many reads the last noise-floor load took to complete. */
    var lastNfPolls = 0
        private set

    private val isRev10 get() = macRev == AR_SREV_REVISION_9271_10

    // ------------------------------------------------------------------ EEPROM fields (ar5416_eeprom_4k, little endian)
    private fun e8(off: Int) = eeprom[off].toInt() and 0xFF
    private fun e16(off: Int) = e8(off) or (e8(off + 1) shl 8)
    private fun e32(off: Int) = e16(off) or (e16(off + 2) shl 16)
    private fun lo(off: Int) = e8(off) and 0x0F
    private fun hi(off: Int) = e8(off) ushr 4
    private val eepRev get() = e16(EEP_VERSION) and 0x0FFF

    // ------------------------------------------------------------------ init (__ath9k_hw_init and what ath9k_htc does around it)
    /** Wakes the chip, reads its revision and EEPROM. Throws when the chip doesn't answer as an AR9271. */
    fun init() {
        val srev = r.read(AR_SREV.v)
        if (srev == -1) throw Ath9kException("Couldn't read the chip revision")
        if (srev and 0xFF == 0xFF) {  // ath9k_hw_read_revisions
            macVersion = (srev and AR_SREV_VERSION2.v) ushr 12
            macRev = (srev and AR_SREV_REVISION2.v) ushr 8
        } else {
            macVersion = (srev and 0xF0) ushr 4
            macRev = srev and 0x0F
        }
        if (macVersion != AR_SREV_VERSION_9271) throw Ath9kException("Not an AR9271 (MAC version 0x%x)".format(macVersion))
        if (!setResetReg(RESET_POWER_ON)) throw Ath9kException("Couldn't reset the chip")
        if (!setPowerAwake()) throw Ath9kException("Couldn't wake the chip")
        r.read(AR_PHY_CHIP_ID.v)
        rfClaim()
        readEeprom()
        // ath9k_hw_ani_init → ath9k_ani_restart and ath9k_enable_mib_counters, as the trace of Linux shows them
        aniRestart()
        enableMibCounters()
        txGainHigh = e8(EEP_TX_GAIN_TYPE) == 1  // ar9271_hw_init_txgain_ini
        for (i in 0 until 3) {  // ath9k_hw_init_macaddr
            macAddress[2 * i] = eeprom[EEP_MAC + 2 * i]
            macAddress[2 * i + 1] = eeprom[EEP_MAC + 2 * i + 1]
        }
        log("AR9271 rev %d, EEPROM %d.%d, %s tx gain, MAC %s".format(macRev, e16(EEP_VERSION) ushr 12, eepRev,
            if (txGainHigh) "high-power" else "normal", macAddress.joinToString(":") { "%02x".format(it) }))
    }

    /** ar9002_hw_rf_claim / ar9002_hw_get_radiorev */
    private fun rfClaim() {
        r.write(AR_PHY(0), 0x00000007)
        r.write(AR_PHY(0x36), 0x00007058)
        repeat(8) { r.write(AR_PHY(0x20), 0x00010000) }
        r.read(AR_PHY(256))
    }

    /** __ath9k_hw_usb_4k_fill_eeprom: 188 words from offset 64, read 8 at a time; then the magic (ath_usb_eeprom_read). */
    private fun readEeprom() {
        val words = IntArray(EEPROM_WORDS)
        var i = 0
        while (i < EEPROM_WORDS) {
            val n = minOf(8, EEPROM_WORDS - i)
            val v = r.readMulti(IntArray(n) { AR5416_EEPROM_OFFSET + ((i + it + 64) shl 2) })
            for (k in 0 until n) words[i + k] = v[k] and 0xFFFF
            i += n
        }
        eeprom = ByteArray(EEPROM_WORDS * 2) { (words[it / 2] ushr (8 * (it % 2))).toByte() }
        r.read(AR5416_EEPROM_OFFSET)
        r.waitFor(AR_EEPROM_STATUS_DATA.v, AR_EEPROM_STATUS_DATA_BUSY.v or AR_EEPROM_STATUS_DATA_PROT_ACCESS.v, 0)
        val magic = r.read(AR_EEPROM_STATUS_DATA.v) and 0xFFFF
        if (magic != 0xA55A && magic != 0x5AA5) log("Unexpected EEPROM magic 0x%04x".format(magic))
        if (e16(EEP_VERSION) ushr 12 != 0xE) log("Unexpected EEPROM version 0x%04x".format(e16(EEP_VERSION)))
    }

    // ------------------------------------------------------------------ resets and power (hw.c)
    /** ath9k_hw_set_reset_reg */
    private fun setResetReg(type: Int): Boolean {
        r.write(AR_RTC_FORCE_WAKE.v, AR_RTC_FORCE_WAKE_EN.v or AR_RTC_FORCE_WAKE_ON_INT.v)
        return if (!resetPowerOn || type == RESET_POWER_ON) setResetPowerOn().also { if (it) resetPowerOn = true } else setReset(type)
    }

    /** ath9k_hw_set_reset_power_on */
    private fun setResetPowerOn(): Boolean {
        r.write(AR_RTC_FORCE_WAKE.v, AR_RTC_FORCE_WAKE_EN.v or AR_RTC_FORCE_WAKE_ON_INT.v)
        r.write(AR_RC.v, AR_RC_AHB.v)
        r.write(AR_RTC_RESET.v, 0)
        r.delayUs(2)
        r.write(AR_RC.v, 0)
        r.write(AR_RTC_RESET.v, 1)
        if (!r.waitFor(AR_RTC_STATUS.v, AR_RTC_STATUS_M.v, AR_RTC_STATUS_ON.v, AH_WAIT_TIMEOUT)) return false
        return setReset(RESET_WARM)
    }

    /** ath9k_hw_set_reset */
    private fun setReset(type: Int): Boolean {
        r.write(AR_RTC_FORCE_WAKE.v, AR_RTC_FORCE_WAKE_EN.v or AR_RTC_FORCE_WAKE_ON_INT.v)
        val cause = r.read(AR_INTR_SYNC_CAUSE.v) and (AR_INTR_SYNC_LOCAL_TIMEOUT.v or AR_INTR_SYNC_RADM_CPL_TIMEOUT.v)
        if (cause != 0) {
            r.write(AR_INTR_SYNC_ENABLE.v, 0)
            r.write(AR_RC.v, AR_RC_HOSTIF.v or AR_RC_AHB.v)
        } else {
            r.write(AR_RC.v, AR_RC_AHB.v)
        }
        var flags = AR_RTC_RC_MAC_WARM.v
        if (type == RESET_COLD) flags = flags or AR_RTC_RC_MAC_COLD.v
        r.write(AR_RTC_RC.v, flags)
        r.delayUs(100)
        r.write(AR_RTC_RC.v, 0)
        if (!r.waitFor(AR_RTC_RC.v, AR_RTC_RC_M.v, 0, AH_WAIT_TIMEOUT)) return false
        r.write(AR_RC.v, 0)
        return true
    }

    /** ath9k_hw_set_power_awake */
    private fun setPowerAwake(): Boolean {
        if (awake) return true
        if (r.read(AR_RTC_STATUS.v) and AR_RTC_STATUS_M.v == AR_RTC_STATUS_SHUTDOWN.v) {
            if (!setResetReg(RESET_POWER_ON)) return false
            initPll(null)
        }
        r.setBit(AR_RTC_FORCE_WAKE.v, AR_RTC_FORCE_WAKE_EN.v)
        r.delayUs(50)
        var i = POWER_UP_TIME / 50
        while (i > 0) {
            if (r.read(AR_RTC_STATUS.v) and AR_RTC_STATUS_M.v == AR_RTC_STATUS_ON.v) break
            r.delayUs(50)
            r.setBit(AR_RTC_FORCE_WAKE.v, AR_RTC_FORCE_WAKE_EN.v)
            i--
        }
        if (i == 0) return false
        r.clrBit(AR_STA_ID1.v, AR_STA_ID1_PWR_SAV.v)
        awake = true
        return true
    }

    /** ath9k_hw_init_pll with ar9002_hw_compute_pll_control (2.4 GHz, full rate) */
    private fun initPll(@Suppress("UNUSED_PARAMETER") chan: Channel?) {
        val pll = ((5 shl AR_RTC_9160_PLL_REFDIV_S) and AR_RTC_9160_PLL_REFDIV.v) or ((0x2c shl AR_RTC_9160_PLL_DIV_S) and AR_RTC_9160_PLL_DIV.v)
        r.write(AR_RTC_PLL_CONTROL.v, pll)
        r.delayUs(500)  // switch the AR9271's core clock to 117 MHz
        r.write(0x50040, 0x304)
        r.delayUs(RTC_PLL_SETTLE_DELAY)
        r.write(AR_RTC_SLEEP_CLK.v, AR_RTC_FORCE_DERIVED_CLK.v)
    }

    /** ath9k_hw_chip_reset */
    private fun chipReset(chan: Channel): Boolean {
        var type = RESET_WARM
        if (chipFullSleep || r.read(AR_Q_TXE.v) != 0 || (r.read(AR_CR.v) and AR_CR_RXE.v) != 0) type = RESET_COLD
        if (!setResetReg(type)) return false
        if (!setPowerAwake()) return false  // a no-op while Linux's power state says awake, as it does here
        chipFullSleep = false
        initPll(chan)
        return true
    }

    /** ath9k_hw_disable: what Linux leaves the chip in after probing it. */
    fun disable() {
        setPowerAwake()
        setResetReg(RESET_COLD)
        initPll(null)
    }

    /** ath9k_hw_phy_disable */
    fun phyDisable() {
        setResetReg(RESET_WARM)
        initPll(null)
        htcResetInit = true
    }

    // ------------------------------------------------------------------ ath9k_hw_reset (the channel change)
    /** Sets up the chip for [chan] from scratch: what ath9k_hw_reset does when not doing a fast channel change. */
    fun reset(chan: Channel) {
        if (!setPowerAwake()) throw Ath9kException("Couldn't wake the chip")
        curChan?.let { getNf() }
        val saveDefAntenna = r.read(AR_DEF_ANTENNA.v).let { if (it == 0) 1 else it }
        val macStaId1 = r.read(AR_STA_ID1.v) and AR_STA_ID1_BASE_RATE_11B.v
        val tsf = getTsf64()
        val tsfAt = System.nanoTime()
        val saveLedState = r.read(AR_CFG_LED.v) and (AR_CFG_LED_ASSOC_CTL.v or AR_CFG_LED_MODE_SEL.v or
            AR_CFG_LED_BLINK_THRESH_SEL.v or AR_CFG_LED_BLINK_SLOW.v)
        r.write(AR_PHY_ACTIVE.v, AR_PHY_ACTIVE_DIS.v)  // ar5008_hw_mark_phy_inactive
        if (htcResetInit) {
            r.write(AR9271_RESET_POWER_DOWN_CONTROL.v, AR9271_RADIO_RF_RST.v)
            r.delayUs(50)
        }
        if (!chipReset(chan)) throw Ath9kException("Chip reset failed")
        if (htcResetInit) {
            htcResetInit = false
            r.write(AR9271_RESET_POWER_DOWN_CONTROL.v, AR9271_GATE_MAC_CTL.v)
            r.delayUs(50)
        }
        setTsf64(tsf + (System.nanoTime() - tsfAt) / 1000)
        r.setBit(AR_GPIO_INPUT_EN_VAL.v, AR_GPIO_JTAG_DISABLE.v)
        processIni(chan)
        r.write(AR_PHY_MODE.v, AR_PHY_MODE_DYNAMIC.v)  // ar5008_hw_set_rfmode, 2.4 GHz
        // ath9k_hw_init_mfp
        r.rmwField(AR_AES_MUTE_MASK1.v, AR_AES_MUTE_MASK1_FC_MGMT.v, 0xc7ff)
        setDeltaSlope(chan)
        spurMitigate(chan)
        setBoardValues(chan)
        resetOpmode(macStaId1, saveDefAntenna)
        setChannel(chan)
        clockRate = ATH9K_CLOCK_RATE_2GHZ_OFDM * (1)  // ath9k_hw_set_clockrate (HT20/legacy)
        initQueueMasks()
        initInterruptMasks()
        aniCacheIniRegs()
        initQos()
        initGlobalSettings(chan)
        r.setBit(AR_STA_ID1.v, AR_STA_ID1_PRESERVE_SEQNUM.v)
        setDma()
        r.write(AR_OBS.v, 8)
        // rx interrupt mitigation (config.rx_intr_mitigation)
        r.rmwField(AR_RIMT.v, AR_RIMT_LAST.v, 250)
        r.rmwField(AR_RIMT.v, AR_RIMT_FIRST.v, 700)
        initBb(chan)
        if (!initCal(chan)) throw Ath9kException("Calibration didn't complete on %d MHz".format(chan.freqMhz))
        r.write(AR_CFG_LED.v, saveLedState or AR_CFG_SCLK_32KHZ.v)
        r.write(AR_CFG.v, AR_CFG_SWRB.v or AR_CFG_SWTB.v)  // ath9k_hw_init_desc for the AR9271
        r.flush()
        curChan = chan
    }

    /** ar5008_hw_process_ini */
    private fun processIni(chan: Channel) {
        val modesIndex = 4  // 2.4 GHz, 20 MHz
        r.write(AR_PHY(0), 0x00000007)
        r.write(AR_PHY_ADC_SERIAL_CTL.v, AR_PHY_SEL_EXTERNAL_RADIO.v)
        r.write(AR_PHY_ADC_SERIAL_CTL.v, AR_PHY_SEL_INTERNAL_ADDAC.v)
        writeIni(Initvals.modes, modesIndex)
        writeIni(if (txGainHigh) Initvals.modesHighPowerTxGain else Initvals.modesNormalPowerTxGain, modesIndex)
        if (isRev10) {
            r.setBit(AR_PHY_SPECTRAL_SCAN.v, AR_PHY_SPECTRAL_SCAN_ENA.v)
            r.rmwField(AR_PHY_RF_CTL3.v, AR_PHY_TX_END_TO_ADC_ON.v, 0xa)
        }
        writeIni(Initvals.common, 1)
        // ar5008_hw_override_ini
        r.setBit(AR_DIAG_SW.v, AR_DIAG_RX_DIS.v or AR_DIAG_RX_ABORT.v)
        val misc = (r.read(AR_PCU_MISC_MODE2.v) and AR_ADHOC_MCAST_KEYID_ENABLE.v.inv()) or AR_PCU_MISC_MODE2_CFP_IGNORE.v
        r.write(AR_PCU_MISC_MODE2.v, misc)
        setChannelRegs()
        // ar5008_hw_init_chain_masks, rx chainmask 1
        r.write(AR_PHY_RX_CHAINMASK.v, rxChainmask)
        r.write(AR_PHY_CAL_CHAINMASK.v, rxChainmask)
        r.write(AR_SELFGEN_MASK.v, txChainmask)
        // ath9k_hw_apply_txpower is left out: transmit power only matters when transmitting.
    }

    /** ar5008_hw_set_channel_regs (20 MHz) */
    private fun setChannelRegs() {
        val dac = r.read(AR_PHY_TURBO.v) and AR_PHY_FC_ENABLE_DAC_FIFO.v
        r.write(AR_PHY_TURBO.v, AR_PHY_FC_HT_EN.v or AR_PHY_FC_SHORT_GI_40.v or AR_PHY_FC_SINGLE_HT_LTF1.v or AR_PHY_FC_WALSH.v or dac)
        r.write(AR_2040_MODE.v, 0)
        r.write(AR_GTXTO.v, 25 shl AR_GTXTO_TIMEOUT_LIMIT_S)
        r.write(AR_CST.v, 0xF shl AR_CST_TIMEOUT_LIMIT_S)
        r.flush()
    }

    /**
     * ath9k_hw_do_fastcc with ath9k_hw_channel_change: retunes without resetting the chip, as Linux does when scanning.
     * About a tenth of a full reset's register traffic. Returns false when it can't be used (no channel yet, the same
     * channel, frames waiting to be sent, the RF bus not granted); then do a full [reset].
     */
    fun fastChannelChange(chan: Channel): Boolean {
        val cur = curChan ?: return false
        if (cur.freqMhz == chan.freqMhz || cur.ht != chan.ht) return false
        if (!setPowerAwake()) return false
        getNf()
        if (r.read(AR_CFG.v) == 0xdeadbeef.toInt()) return false  // ath9k_hw_check_alive
        for (q in 0 until AR_NUM_QCU) {  // ath9k_hw_numtxpending
            if (r.read(AR_QSTS(q)) and AR_Q_STS_PEND_FR_CNT.v == 0 && r.read(AR_Q_TXE.v) and (1 shl q) != 0) return false
        }
        r.write(AR_PHY_RFBUS_REQ.v, AR_PHY_RFBUS_REQ_EN.v)  // ar5008_hw_rfbus_req
        if (!r.waitFor(AR_PHY_RFBUS_GRANT.v, AR_PHY_RFBUS_GRANT_EN.v, AR_PHY_RFBUS_GRANT_EN.v, AH_WAIT_TIMEOUT)) return false
        setChannelRegs()
        setChannel(chan)
        // (transmit power left out, as in reset)
        setDeltaSlope(chan)
        spurMitigate(chan)
        initBb(chan)
        val synthDelay = r.read(AR_PHY_RX_DELAY.v) and AR_PHY_RX_DELAY_DELAY.v  // ar5008_hw_rfbus_done
        r.delayUs(synthDelay / 10 + BASE_ACTIVATE_DELAY)
        r.write(AR_PHY_RFBUS_REQ.v, 0)
        loadNf()
        startNfCal()
        loadAniReg()
        r.flush()
        curChan = chan
        return true
    }

    /** ar9002_hw_load_ani_reg (2.4 GHz, 20 MHz column) */
    private fun loadAniReg() {
        val ini = Initvals.modesANIReg
        for (row in 0 until ini.rows) {
            val reg = ini.reg(row)
            var v = ini.value(row, 4)
            if (reg == AR_PHY_CCK_DETECT.v) {
                val orig = r.read(reg) and AR_PHY_CCK_DETECT_WEAK_SIG_THR_CCK.v.inv()
                v = (v and AR_PHY_CCK_DETECT_WEAK_SIG_THR_CCK.v) or orig
            }
            r.write(reg, v)
        }
    }

    /** ath9k_hw_start_nfcal(update = true) */
    private fun startNfCal() {
        r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_ENABLE_NF.v)
        r.clrBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NO_UPDATE_NF.v)
        r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NF.v)
    }

    private fun writeIni(ini: Ini, column: Int) {
        for (row in 0 until ini.rows) {
            r.write(ini.reg(row), ini.value(row, column))
        }
        r.flush()
    }

    /** ar5008_hw_set_delta_slope with ath9k_hw_get_delta_slope_vals */
    private fun setDeltaSlope(chan: Channel) {
        var coef = 0x64000000 / chan.freqMhz
        var (man, exp) = deltaSlopeVals(coef)
        r.rmwField(AR_PHY_TIMING3.v, AR_PHY_TIMING3_DSC_MAN.v, man)
        r.rmwField(AR_PHY_TIMING3.v, AR_PHY_TIMING3_DSC_EXP.v, exp)
        coef = 9 * coef / 10
        deltaSlopeVals(coef).let { man = it.first; exp = it.second }
        r.rmwField(AR_PHY_HALFGI.v, AR_PHY_HALFGI_DSC_MAN.v, man)
        r.rmwField(AR_PHY_HALFGI.v, AR_PHY_HALFGI_DSC_EXP.v, exp)
    }

    private fun deltaSlopeVals(coefScaled: Int): Pair<Int, Int> {
        var e = 31
        while (e > 0 && (coefScaled ushr e) and 1 == 0) e--
        val coefExp = 14 - (e - COEF_SCALE_S)
        val coefMan = coefScaled + (1 shl (COEF_SCALE_S - coefExp - 1))
        return (coefMan ushr (COEF_SCALE_S - coefExp)) to (coefExp - 16)
    }

    /** ar9002_hw_spur_mitigate: only the "no spur near this channel" case is ported; see spurNear. */
    private fun spurMitigate(chan: Channel) {
        val spur = spurNear(chan)
        r.clrBit(AR_PHY_FORCE_CLKEN_CCK.v, AR_PHY_FORCE_CLKEN_CCK_MRC_MUX.v)
        if (spur != null) log("The EEPROM lists a spur %d MHz from %d MHz; spur mitigation isn't ported, reception may be weaker there".format(spur, chan.freqMhz))
    }

    /** The first EEPROM spur channel within ±10 MHz of [chan], as an offset, or null. */
    private fun spurNear(chan: Channel): Int? {
        for (i in 0 until AR_EEPROM_MODAL_SPURS) {
            val raw = e16(EEP_SPURS + 4 * i)
            if (raw == AR_NO_SPUR) break
            val off = raw / 10 + AR_BASE_FREQ_2GHZ - chan.freqMhz
            if (off > -AR_SPUR_FEEQ_BOUND_HT20 && off < AR_SPUR_FEEQ_BOUND_HT20) return off
        }
        return null
    }

    /** ath9k_hw_4k_set_board_values with ath9k_hw_4k_set_gain */
    private fun setBoardValues(chan: Channel) {
        val m = EEP_MODAL
        r.write(AR_PHY_SWITCH_COM.v, e32(m + 4))
        // ath9k_hw_4k_set_gain
        var txRxAtten = 23
        r.rmw(AR_PHY_SWITCH_CHAIN_0.v, e32(m), 0)
        r.rmw(AR_PHY_TIMING_CTRL4(0), (e8(m + 22) shl AR_PHY_TIMING_CTRL4_IQCORR_Q_I_COFF_S and AR_PHY_TIMING_CTRL4_IQCORR_Q_I_COFF.v) or
            (e8(m + 23) shl AR_PHY_TIMING_CTRL4_IQCORR_Q_Q_COFF_S and AR_PHY_TIMING_CTRL4_IQCORR_Q_Q_COFF.v),
            AR_PHY_TIMING_CTRL4_IQCORR_Q_Q_COFF.v or AR_PHY_TIMING_CTRL4_IQCORR_Q_I_COFF.v)
        if (eepRev >= 3) {
            txRxAtten = e8(m + 10)
            for (base in listOf(AR_PHY_GAIN_2GHZ.v, AR_PHY_GAIN_2GHZ.v + 0x1000)) {
                r.rmwField(base, AR_PHY_GAIN_2GHZ_XATTEN1_MARGIN.v, e8(m + 32))
                r.rmwField(base, AR_PHY_GAIN_2GHZ_XATTEN1_DB.v, e8(m + 31))
                r.rmwField(base, AR_PHY_GAIN_2GHZ_XATTEN2_MARGIN.v, e8(m + 35))
                r.rmwField(base, AR_PHY_GAIN_2GHZ_XATTEN2_DB.v, e8(m + 34))
            }
        }
        for (base in listOf(AR_PHY_RXGAIN.v, AR_PHY_RXGAIN.v + 0x1000)) {
            r.rmwField(base, AR9280_PHY_RXGAIN_TXRX_ATTEN.v, txRxAtten)
            r.rmwField(base, AR9280_PHY_RXGAIN_TXRX_MARGIN.v, e8(m + 11))
        }
        val version = e8(m + 37)
        if (version >= 3) {  // antenna diversity settings
            val ctl1 = hi(m + 39)
            val ctl2 = hi(m + 41)
            var v = r.read(AR_PHY_MULTICHAIN_GAIN_CTL.v) and AR_PHY_9285_ANT_DIV_CTL_ALL.v.inv()
            v = v or sm(ctl1, AR_PHY_9285_ANT_DIV_CTL) or sm(ctl2, AR_PHY_9285_ANT_DIV_ALT_LNACONF) or
                sm(ctl2 ushr 2, AR_PHY_9285_ANT_DIV_MAIN_LNACONF) or sm(ctl1 ushr 1, AR_PHY_9285_ANT_DIV_ALT_GAINTB) or
                sm(ctl1 ushr 2, AR_PHY_9285_ANT_DIV_MAIN_GAINTB)
            r.write(AR_PHY_MULTICHAIN_GAIN_CTL.v, v)
            r.read(AR_PHY_MULTICHAIN_GAIN_CTL.v)
            v = r.read(AR_PHY_CCK_DETECT.v) and AR_PHY_CCK_DETECT_BB_ENABLE_ANT_FAST_DIV.v.inv()
            v = v or sm(ctl1 ushr 3, AR_PHY_CCK_DETECT_BB_ENABLE_ANT_FAST_DIV)
            r.write(AR_PHY_CCK_DETECT.v, v)
            r.read(AR_PHY_CCK_DETECT.v)
        }
        val ob = IntArray(3)
        val db1: Int
        val db2: Int
        when {
            version >= 2 -> { ob[0] = lo(m + 25); ob[1] = hi(m + 25); ob[2] = lo(m + 38); db1 = lo(m + 26); db2 = lo(m + 36) }
            version == 1 -> { ob[0] = lo(m + 25); ob[1] = hi(m + 25); ob[2] = hi(m + 25); db1 = lo(m + 26); db2 = lo(m + 36) }
            else -> { ob.fill(lo(m + 25)); db1 = lo(m + 26); db2 = lo(m + 26) }
        }
        analogShiftRmw(AR9285_AN_RF2G3.v, AR9271_AN_RF2G3_OB_cck.v, AR9271_AN_RF2G3_OB_cck_S, ob[0])
        analogShiftRmw(AR9285_AN_RF2G3.v, AR9271_AN_RF2G3_OB_psk.v, AR9271_AN_RF2G3_OB_psk_S, ob[1])
        analogShiftRmw(AR9285_AN_RF2G3.v, AR9271_AN_RF2G3_OB_qam.v, AR9271_AN_RF2G3_OB_qam_S, ob[2])
        analogShiftRmw(AR9285_AN_RF2G3.v, AR9271_AN_RF2G3_DB_1.v, AR9271_AN_RF2G3_DB_1_S, db1)
        analogShiftRmw(AR9285_AN_RF2G4.v, AR9271_AN_RF2G4_DB_2.v, AR9271_AN_RF2G4_DB_2_S, db2)
        r.rmwField(AR_PHY_SETTLING.v, AR_PHY_SETTLING_SWITCH.v, e8(m + 9))
        r.rmwField(AR_PHY_DESIRED_SZ.v, AR_PHY_DESIRED_SZ_ADC.v, e8(m + 12))
        r.rmw(AR_PHY_RF_CTL4.v, sm(e8(m + 15), AR_PHY_RF_CTL4_TX_END_XPAA_OFF) or sm(e8(m + 15), AR_PHY_RF_CTL4_TX_END_XPAB_OFF) or
            sm(e8(m + 17), AR_PHY_RF_CTL4_FRAME_XPAA_ON) or sm(e8(m + 17), AR_PHY_RF_CTL4_FRAME_XPAB_ON), 0)
        r.rmwField(AR_PHY_RF_CTL3.v, AR_PHY_TX_END_TO_A2_RX_ON.v, e8(m + 16))
        if (isRev10) r.rmwField(AR_PHY_RF_CTL3.v, AR_PHY_TX_END_TO_A2_RX_ON.v, e8(m + 16))
        r.rmwField(AR_PHY_CCA.v, AR9280_PHY_CCA_THRESH62.v, e8(m + 18))
        r.rmwField(AR_PHY_EXT_CCA0.v, AR_PHY_EXT_CCA0_THRESH62.v, e8(m + 18))
        if (eepRev >= 2) {
            r.rmwField(AR_PHY_RF_CTL2.v, AR_PHY_TX_END_DATA_START.v, e8(m + 28))
            r.rmwField(AR_PHY_RF_CTL2.v, AR_PHY_TX_END_PA_ON.v, e8(m + 29))
        }
        // (HT40 settling time: HT40 isn't used)
        val scale = e8(m + 46) and 0x1f
        if (e8(EEP_TX_GAIN_TYPE) == 0 && scale != 0) {
            var mask = 1 or (1 shl 5) or (1 shl 10) or (1 shl 15) or (1 shl 20) or (1 shl 25)
            r.rmw(AR_PHY_TX_PWRCTRL8.v, mask * scale, mask * 0x1f)
            r.rmw(AR_PHY_TX_PWRCTRL10.v, mask * scale, mask * 0x1f)
            r.rmw(AR_PHY_CH0_TX_PWRCTRL12.v, mask * scale, mask * 0x1f)
            mask = 1 or (1 shl 5) or (1 shl 15)
            r.rmw(AR_PHY_TX_PWRCTRL9.v, mask * scale, mask * 0x1f)
            mask = 1 or (1 shl 5)
            r.rmw(AR_PHY_CH0_TX_PWRCTRL11.v, mask * scale, mask * 0x1f)
            r.rmw(AR_PHY_CH0_TX_PWRCTRL13.v, mask * scale, mask * 0x1f)
        }
    }

    private fun sm(v: Int, f: R) = (v shl Integer.numberOfTrailingZeros(f.v)) and f.v

    /** ath9k_hw_analog_shift_rmw */
    private fun analogShiftRmw(reg: Int, mask: Int, shift: Int, v: Int) {
        r.rmw(reg, (v shl shift) and mask, mask)
        r.delayUs(100)
    }

    /** ath9k_hw_reset_opmode with ath_hw_setbssidmask, ath9k_hw_write_associd and ath9k_hw_set_operating_mode (monitor) */
    private fun resetOpmode(macStaId1: Int, saveDefAntenna: Int) {
        r.rmw(AR_STA_ID1.v, macStaId1 or AR_STA_ID1_RTS_USE_DEF.v or staId1Defaults, AR_STA_ID1_SADH_MASK.v.inv())
        r.write(AR_STA_ID0.v, le32(macAddress, 0))
        val id1 = (r.read(AR_STA_ID1.v) and AR_STA_ID1_SADH_MASK.v.inv()) or le16(macAddress, 4)
        r.write(AR_STA_ID1.v, id1)
        r.write(AR_BSSMSKL.v, le32(bssidMask, 0))
        r.write(AR_BSSMSKU.v, le16(bssidMask, 4))
        r.write(AR_DEF_ANTENNA.v, saveDefAntenna)
        r.write(AR_BSS_ID0.v, 0)
        r.write(AR_BSS_ID1.v, 0)
        r.write(AR_ISR.v, -1)
        r.write(AR_RSSI_THR.v, INIT_RSSI_THR)
        // monitor: KSRCH_MODE kept, not AP or ad-hoc
        r.rmw(AR_STA_ID1.v, AR_STA_ID1_KSRCH_MODE.v, AR_STA_ID1_STA_AP.v or AR_STA_ID1_ADHOC.v)
    }

    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** ar9002_hw_set_channel, 2.4 GHz fractional mode */
    private fun setChannel(chan: Channel) {
        val freq = chan.freqMhz
        var reg32 = r.read(AR_PHY_SYNTH_CONTROL.v) and 0xc0000000.toInt()
        val txctl = r.read(AR_PHY_CCK_TX_CTRL.v)
        r.write(AR_PHY_CCK_TX_CTRL.v, if (freq == 2484) txctl or AR_PHY_CCK_TX_CTRL_JAPAN.v else txctl and AR_PHY_CCK_TX_CTRL_JAPAN.v.inv())
        val channelSel = (freq * 0x10000) / 15
        reg32 = reg32 or (1 shl 29) or (1 shl 28) or channelSel
        r.write(AR_PHY_SYNTH_CONTROL.v, reg32)
    }

    /** ath9k_hw_init_queues: the queue/DCU masks. The per-queue settings (ath9k_hw_resettxqueue) are for transmitting. */
    private fun initQueueMasks() {
        for (i in 0 until AR_NUM_DCU) r.write(AR_DQCUMASK(i), 1 shl i)
    }

    /** ath9k_hw_init_interrupt_masks: the firmware takes the interrupts, so it needs these to receive. */
    private fun initInterruptMasks() {
        val imr = AR_IMR_TXERR.v or AR_IMR_TXURN.v or AR_IMR_RXERR.v or AR_IMR_RXORN.v or AR_IMR_BCNMISC.v or
            AR_IMR_RXINTM.v or AR_IMR_RXMINTR.v or AR_IMR_TXOK.v
        r.write(AR_IMR.v, imr)
        r.write(AR_IMR_S2.v, AR_IMR_S2_GTT.v)
        r.write(AR_INTR_SYNC_CAUSE.v, -1)
        r.write(AR_INTR_SYNC_ENABLE.v, AR_INTR_SYNC_DEFAULT.v)
        r.write(AR_INTR_SYNC_MASK.v, 0)
        r.flush()
    }

    /** ar5008_hw_ani_cache_ini_regs only reads; kept so the register traffic matches Linux's. */
    private fun aniCacheIniRegs() {
        r.read(AR_PHY_SFCORR.v)
        r.read(AR_PHY_SFCORR_LOW.v)
        r.read(AR_PHY_SFCORR_EXT.v)
        r.read(AR_PHY_FIND_SIG.v)
        r.read(AR_PHY_FIND_SIG_LOW.v)
        r.read(AR_PHY_TIMING5.v)
        r.read(AR_PHY_EXT_CCA.v)
    }

    /** ath9k_hw_init_qos */
    private fun initQos() {
        r.write(AR_MIC_QOS_CONTROL.v, 0x100aa)
        r.write(AR_MIC_QOS_SELECT.v, 0x3210)
        r.write(AR_QOS_NO_ACK.v, (2 shl AR_QOS_NO_ACK_TWO_BIT_S) or (5 shl AR_QOS_NO_ACK_BIT_OFF_S) or (0 shl AR_QOS_NO_ACK_BYTE_OFF_S))
        r.write(AR_TXOP_X.v, AR_TXOP_X_VAL.v)
        r.write(AR_TXOP_0_3.v, -1)
        r.write(AR_TXOP_4_7.v, -1)
        r.write(AR_TXOP_8_11.v, -1)
        r.write(AR_TXOP_12_15.v, -1)
        r.flush()
    }

    /** ath9k_hw_init_global_settings for a 2.4 GHz, full-rate channel */
    private fun initGlobalSettings(@Suppress("UNUSED_PARAMETER") chan: Channel) {
        r.setBit(AR_PCU_MISC, AR_PCU_MIC_NEW_LOC_ENA)  // ah->misc_mode, from ath9k_hw_fill_cap_info
        val sifs = 10
        val eifs = r.read(AR_D_GBL_IFS_EIFS.v) / clockRate
        val usec = r.read(AR_USEC.v)
        val rxLat = (usec and AR_USEC_RX_LAT.v) ushr AR_USEC_RX_LAT_S
        val txLat = (usec and AR_USEC_TX_LAT.v) ushr AR_USEC_TX_LAT_S
        val slot = slotTime
        var ack = slot + sifs
        var cts = ack
        ack += 64 - sifs - slotTime
        cts += 48 - sifs - slotTime
        r.write(AR_D_GBL_IFS_SIFS.v, minOf((sifs - 2) * clockRate, 0xFFFF))
        r.write(AR_D_GBL_IFS_SLOT.v, minOf(slot * clockRate, 0xFFFF))
        r.rmwField(AR_TIME_OUT.v, AR_TIME_OUT_ACK.v, ack * clockRate)
        r.rmwField(AR_TIME_OUT.v, AR_TIME_OUT_CTS.v, cts * clockRate)
        r.write(AR_D_GBL_IFS_EIFS.v, eifs * clockRate)
        r.rmw(AR_USEC.v, (clockRate - 1) or (rxLat shl AR_USEC_RX_LAT_S and AR_USEC_RX_LAT.v) or (txLat shl AR_USEC_TX_LAT_S and AR_USEC_TX_LAT.v),
            AR_USEC_TX_LAT.v or AR_USEC_RX_LAT.v or AR_USEC_USEC.v)
    }

    /** ath9k_hw_set_dma */
    private fun setDma() {
        r.setBit(AR_AHB_MODE.v, AR_AHB_PREFETCH_RD_EN.v)
        r.rmw(AR_TXCFG.v, AR_TXCFG_DMASZ_128B.v, AR_TXCFG_DMASZ_MASK.v)
        r.rmwField(AR_TXCFG.v, AR_FTRIG.v, AR_FTRIG_256B.v ushr AR_FTRIG_S)
        r.rmw(AR_RXCFG.v, AR_RXCFG_DMASZ_128B.v, AR_RXCFG_DMASZ_MASK.v)
        r.write(AR_RXFIFO_CFG.v, 0x200)
        r.flush()
    }

    /** ar5008_hw_init_bb with ath9k_hw_synth_delay */
    private fun initBb(@Suppress("UNUSED_PARAMETER") chan: Channel) {
        val synthDelay = r.read(AR_PHY_RX_DELAY.v) and AR_PHY_RX_DELAY_DELAY.v
        r.write(AR_PHY_ACTIVE.v, AR_PHY_ACTIVE_EN.v)
        r.delayUs(synthDelay / 10 + BASE_ACTIVATE_DELAY)
    }

    /** ar9002_hw_init_cal for the AR9271: ar9285_hw_cl_cal, the PA calibration, then loading the noise floor. */
    private fun initCal(chan: Channel): Boolean {
        r.setBit(AR_PHY_CL_CAL_CTL.v, AR_PHY_CL_CAL_ENABLE.v)
        if (chan.ht) {
            r.setBit(AR_PHY_CL_CAL_CTL.v, AR_PHY_PARALLEL_CAL_ENABLE.v)
            r.setBit(AR_PHY_TURBO.v, AR_PHY_FC_DYN2040_EN.v)
            r.clrBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_FLTR_CAL.v)
            r.clrBit(AR_PHY_TPCRG1.v, AR_PHY_TPCRG1_PD_CAL_ENABLE.v)
            r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_CAL.v)
            if (!r.waitFor(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_CAL.v, 0, AH_WAIT_TIMEOUT)) return false
            r.clrBit(AR_PHY_TURBO.v, AR_PHY_FC_DYN2040_EN.v)
            r.clrBit(AR_PHY_CL_CAL_CTL.v, AR_PHY_PARALLEL_CAL_ENABLE.v)
            r.clrBit(AR_PHY_CL_CAL_CTL.v, AR_PHY_CL_CAL_ENABLE.v)
        }
        r.clrBit(AR_PHY_ADC_CTL.v, AR_PHY_ADC_CTL_OFF_PWDADC.v)
        r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_FLTR_CAL.v)
        r.setBit(AR_PHY_TPCRG1.v, AR_PHY_TPCRG1_PD_CAL_ENABLE.v)
        r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_CAL.v)
        if (!r.waitFor(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_CAL.v, 0, AH_WAIT_TIMEOUT)) return false
        r.setBit(AR_PHY_ADC_CTL.v, AR_PHY_ADC_CTL_OFF_PWDADC.v)
        r.clrBit(AR_PHY_CL_CAL_CTL.v, AR_PHY_CL_CAL_ENABLE.v)
        r.clrBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_FLTR_CAL.v)
        paCal()
        loadNf()
        startNfCal()
        // ath9k_hw_reset_calibration for the IQ calibration (ar9002_hw_setup_calibration)
        r.rmwField(AR_PHY_TIMING_CTRL4(0), AR_PHY_TIMING_CTRL4_IQCAL_LOG_COUNT_MAX.v, MAX_CAL_SAMPLES_LOG)
        r.write(AR_PHY_CALMODE.v, AR_PHY_CALMODE_IQ.v)
        r.setBit(AR_PHY_TIMING_CTRL4(0), AR_PHY_TIMING_CTRL4_DO_CAL.v)
        return true
    }

    /** ar9271_hw_pa_cal(is_reset = true) */
    private fun paCal() {
        val regs = intArrayOf(AR9285_AN_TOP3.v, AR9285_AN_RXTXBB1.v, AR9285_AN_RF2G1.v, AR9285_AN_RF2G2.v, AR9285_AN_TOP2.v,
            AR9285_AN_RF2G8.v, AR9285_AN_RF2G7.v, AR9285_AN_RF2G3.v)
        val saved = regs.map { r.read(it) }
        r.clrBit(AR9285_AN_RF2G6.v, 1)
        r.setBit(0x9808, 1 shl 27)
        r.setBit(AR9285_AN_TOP3.v, AR9285_AN_TOP3_PWDDAC.v)
        r.setBit(AR9285_AN_RXTXBB1.v, AR9285_AN_RXTXBB1_PDRXTXBB1.v)
        r.setBit(AR9285_AN_RXTXBB1.v, AR9285_AN_RXTXBB1_PDV2I.v)
        r.setBit(AR9285_AN_RXTXBB1.v, AR9285_AN_RXTXBB1_PDDACIF.v)
        r.clrBit(AR9285_AN_RF2G2.v, AR9285_AN_RF2G2_OFFCAL.v)
        r.clrBit(AR9285_AN_RF2G7.v, AR9285_AN_RF2G7_PWDDB.v)
        r.clrBit(AR9285_AN_RF2G1.v, AR9285_AN_RF2G1_ENPACAL.v)
        r.clrBit(AR9285_AN_RF2G1.v, AR9285_AN_RF2G1_PDPADRV1.v)
        r.clrBit(AR9285_AN_RF2G1.v, AR9285_AN_RF2G1_PDPADRV2.v)
        r.clrBit(AR9285_AN_RF2G1.v, AR9285_AN_RF2G1_PDPAOUT.v)
        r.rmwField(AR9285_AN_RF2G8.v, AR9285_AN_RF2G8_PADRVGN2TAB0.v, 7)
        r.rmwField(AR9285_AN_RF2G7.v, AR9285_AN_RF2G7_PADRVGN2TAB0.v, 0)
        r.rmwField(AR9285_AN_RF2G3.v, AR9271_AN_RF2G3_CCOMP.v, 0xfff)
        r.write(AR9285_AN_TOP2.v, 0xca0358a0.toInt())
        r.delayUs(30)
        r.rmwField(AR9285_AN_RF2G6.v, AR9271_AN_RF2G6_OFFS.v, 0)
        var v = 0
        for (i in 6 downTo 1) {
            v = r.read(AR9285_AN_RF2G6.v) or (1 shl (20 + i))
            r.write(AR9285_AN_RF2G6.v, v)
            r.delayUs(1)
            v = v and (1 shl (20 + i)).inv()
            v = v or (((r.read(AR9285_AN_RF2G9.v) and AR9285_AN_RXTXBB1_SPARE9.v) ushr AR9285_AN_RXTXBB1_SPARE9_S) shl (20 + i))
            r.write(AR9285_AN_RF2G6.v, v)
        }
        r.setBit(AR9285_AN_RF2G6.v, 1)
        r.clrBit(0x9808, 1 shl 27)
        for (i in regs.indices) r.write(regs[i], saved[i])
        r.flush()
    }

    /** ath9k_hw_loadnf with the calibration history Linux starts each channel with: the nominal noise floor. */
    private fun loadNf() {
        val nominal = AR_PHY_CCA_NOM_VAL_9271_2GHZ
        val agc = r.read(AR_PHY_AGC_CONTROL.v)
        r.rmw(AR_PHY_CCA.v, (nominal shl 1) and 0x1ff, 0x1ff)  // chain 0 (ctl); chainmask 1 → only nf_regs[0]
        if (agc and AR_PHY_AGC_CONTROL_NF.v != 0) r.clrBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NF.v)
        r.clrBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_ENABLE_NF.v)
        r.clrBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NO_UPDATE_NF.v)
        r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NF.v)
        var loaded = false
        var polls = 0
        for (j in 0 until 22200) {
            polls++
            if (r.read(AR_PHY_AGC_CONTROL.v) and AR_PHY_AGC_CONTROL_NF.v == 0) { loaded = true; break }
            r.delayUs(10)
        }
        lastNfPolls = polls
        if (agc and AR_PHY_AGC_CONTROL_NF.v != 0) {
            if (agc and AR_PHY_AGC_CONTROL_ENABLE_NF.v != 0) r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_ENABLE_NF.v)
            if (agc and AR_PHY_AGC_CONTROL_NO_UPDATE_NF.v != 0) r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NO_UPDATE_NF.v)
            r.setBit(AR_PHY_AGC_CONTROL.v, AR_PHY_AGC_CONTROL_NF.v)
        }
        if (!loaded) return
        r.rmw(AR_PHY_CCA.v, ((-50) shl 1) and 0x1ff, 0x1ff)
    }

    /** ath9k_hw_getnf, only for its register reads (the result isn't used: Linux's rssi reference is a fixed -95 dBm). */
    private fun getNf() {
        if (r.read(AR_PHY_AGC_CONTROL.v) and AR_PHY_AGC_CONTROL_NF.v != 0) return
        r.read(AR_PHY_CCA.v)
        r.read(AR_PHY_EXT_CCA.v)
    }

    private fun getTsf64(): Long {
        var upper = r.read(AR_TSF_U32.v)
        var lower = 0
        for (i in 0 until 10) {
            lower = r.read(AR_TSF_L32.v)
            val upper2 = r.read(AR_TSF_U32.v)
            if (upper2 == upper) break
            upper = upper2
        }
        return (upper.toLong() shl 32) or (lower.toLong() and 0xFFFFFFFFL)
    }

    private fun setTsf64(tsf: Long) {
        r.write(AR_TSF_L32.v, tsf.toInt())
        r.write(AR_TSF_U32.v, (tsf ushr 32).toInt())
    }

    // ------------------------------------------------------------------ receiving (ath9k_host_rx_init)
    /** ath9k_hw_rxena, the monitor receive filter, an open multicast filter and ath9k_hw_startpcureceive. */
    fun startReceive() {
        r.write(AR_CR.v, AR_CR_RXE.v)
        r.read(AR_RX_FILTER.v)
        r.read(AR_PHY_ERR.v)
        r.write(AR_RX_FILTER.v, RX_FILTER_MONITOR)
        r.write(AR_PHY_ERR.v, 0)
        r.clrBit(AR_RXCFG.v, AR_RXCFG_ZLFDMA.v)
        r.write(AR_MCAST_FIL0.v, -1)
        r.write(AR_MCAST_FIL1.v, -1)
        enableMibCounters()
        aniRestart()
        r.clrBit(AR_DIAG_SW.v, AR_DIAG_RX_DIS.v or AR_DIAG_RX_ABORT.v)
    }

    /** ath9k_enable_mib_counters, after ath9k_hw_update_mibstats' reads */
    private fun enableMibCounters() {
        r.readMulti(intArrayOf(AR_RTS_OK.v, AR_RTS_FAIL.v, AR_ACK_FAIL.v, AR_FCS_FAIL.v, AR_BEACON_CNT.v))  // ath9k_hw_update_mibstats
        r.write(AR_FILT_OFDM.v, 0)
        r.write(AR_FILT_CCK.v, 0)
        r.write(AR_MIBC.v, (AR_MIBC_COW.v or AR_MIBC_FMC.v or AR_MIBC_CMC.v or AR_MIBC_MCS.v).inv() and 0x0f)
        r.write(AR_PHY_ERR_MASK_1.v, AR_PHY_ERR_OFDM_TIMING.v)
        r.write(AR_PHY_ERR_MASK_2.v, AR_PHY_ERR_CCK_TIMING.v)
        r.flush()
    }

    /** ath9k_ani_restart: zeroes the phy error counters ANI watches. */
    private fun aniRestart() {
        r.write(AR_PHY_ERR_1.v, 0)
        r.write(AR_PHY_ERR_2.v, 0)
        r.write(AR_PHY_ERR_MASK_1.v, AR_PHY_ERR_OFDM_TIMING.v)
        r.write(AR_PHY_ERR_MASK_2.v, AR_PHY_ERR_CCK_TIMING.v)
        r.readMulti(intArrayOf(AR_RTS_OK.v, AR_RTS_FAIL.v, AR_ACK_FAIL.v, AR_FCS_FAIL.v, AR_BEACON_CNT.v))  // ath9k_hw_update_mibstats
    }

    companion object {
        const val RESET_POWER_ON = 0
        const val RESET_WARM = 1
        const val RESET_COLD = 2

        /** UCAST | MCAST | BCAST | CONTROL | BEACON | PROM | PSPOLL | MCAST_BCAST_ALL, as Linux sets it in monitor mode. */
        const val RX_FILTER_MONITOR = 0xc03f

        const val AR_SREV_VERSION_9271 = 0x140
        const val AR_SREV_REVISION_9271_10 = 0
        const val AH_WAIT_TIMEOUT = 100_000
        const val POWER_UP_TIME = 10_000
        const val RTC_PLL_SETTLE_DELAY = 100
        const val BASE_ACTIVATE_DELAY = 100
        const val COEF_SCALE_S = 24
        const val INIT_RSSI_THR = 0x700
        const val ATH9K_CLOCK_RATE_CCK = 22
        const val ATH9K_CLOCK_RATE_2GHZ_OFDM = 44
        const val AR_BASE_FREQ_2GHZ = 2300
        const val AR_SPUR_FEEQ_BOUND_HT20 = 10
        const val AR_NO_SPUR = 0x8000
        const val AR_EEPROM_MODAL_SPURS = 5
        const val AR_PHY_CCA_NOM_VAL_9271_2GHZ = -118
        /** iq_cal_single_sample's calCountMax (PER_MAX_LOG_COUNT): the AR9271 counts as an AR9280 or later here. */
        const val MAX_CAL_SAMPLES_LOG = 10
        const val AR_NUM_DCU = 10
        const val AR_NUM_QCU = 10
        const val AR5416_EEPROM_OFFSET = 0x2000
        const val AR_PCU_MISC = 0x8120
        const val AR_PCU_MIC_NEW_LOC_ENA = 0x4

        /** Linux reports a received frame's signal as this noise floor plus the chip's RSSI. */
        const val NOISE_FLOOR = -95

        // The 4K EEPROM layout (eeprom.h): byte offsets
        const val EEPROM_WORDS = 188
        const val EEP_VERSION = 4
        const val EEP_MAC = 12
        const val EEP_TX_GAIN_TYPE = 31
        const val EEP_MODAL = 52
        const val EEP_SPURS = EEP_MODAL + 48
    }
}
