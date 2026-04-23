import java.awt.{BasicStroke, BorderLayout, Color, Cursor, Dimension, Font, GradientPaint, Graphics, Graphics2D, GridLayout, RenderingHints}
import java.awt.event.{ActionEvent, ActionListener, MouseAdapter, MouseEvent, MouseMotionAdapter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.border.{EmptyBorder, LineBorder}
import javax.swing.event.ChangeListener
import javax.swing.{BoxLayout, JButton, JFrame, JLabel, JPanel, JScrollPane, JSlider, JTextArea, SwingConstants, SwingUtilities, Timer, ToolTipManager, WindowConstants}
import scala.collection.mutable.ArrayBuffer
import scala.math.{Pi, abs, cos, max, min, sin, sqrt}
import scala.util.Random

object AntimatterAnnihilationGUI:

  enum Channel:
    case GammaGamma, GammaMeson, MesonMeson

  enum Species:
    case Matter, Antimatter

  final case class ScanSummary(filePath: String, rowCount: Int, bestConfig: String, bestEnergyJ: Double)

  final class ChannelAccumulator(val label: String):
    var events: Double = 0.0
    var energyJ: Double = 0.0

    def reset(): Unit =
      events = 0.0
      energyJ = 0.0

  final case class Vec2(x: Double, y: Double):
    def +(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)
    def -(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)
    def *(k: Double): Vec2 = Vec2(x * k, y * k)
    def /(k: Double): Vec2 = Vec2(x / k, y / k)
    def magnitude: Double = sqrt(x * x + y * y)

  final class VisualParticle(val species: Species, var pos: Vec2, var vel: Vec2)

  final class Flash(var pos: Vec2, val baseRadius: Double, val life: Double, val energyJ: Double, val color: Color):
    var age: Double = 0.0

  private final class AntimatterModel(initialWorldWidth: Int, initialWorldHeight: Int):
    var worldWidth: Int = max(240, initialWorldWidth)
    var worldHeight: Int = max(240, initialWorldHeight)

    val c: Double = 299_792_458.0
    val tntJPerTon: Double = 4.184e9
    val u235JPerKg: Double = 8.2e13
    val hiroshimaJ: Double = 6.3e13
    val electronChargeC: Double = 1.602176634e-19
    val electronMassKg: Double = 9.1093837015e-31
    val electronRestEnergyJ: Double = electronMassKg * c * c
    val pairMassKg: Double = 2.0 * electronMassKg
    val photon511EnergyJ: Double = 511_000.0 * electronChargeC
    val pairThresholdMeV: Double = (pairMassKg * c * c) / (electronChargeC * 1.0e6)

    private final case class ScenarioMetrics(
        totalEnergyJ: Double,
        avgPowerW: Double,
        stabilityIndex: Double,
        pairEvents: Double,
        thresholdEvents: Double,
        gammaGammaSharePct: Double,
        gammaMesonSharePct: Double,
        mesonSharePct: Double
    )

    private val random = new Random(19)

    var initialMatterKg: Double = 300e-6
    var initialAntiKg: Double = 300e-6
    var matterKg: Double = initialMatterKg
    var antiKg: Double = initialAntiKg

    var confinement: Double = 0.65
    var fieldGradient: Double = 0.35
    var conversionEfficiency: Double = 0.88
    var reactionGain: Double = 1200.0
    var speedScale: Double = 1.0
    var visualBudget: Int = 360

    var simTimeS: Double = 0.0
    var totalEnergyJ: Double = 0.0
    var instantPowerW: Double = 0.0
    var smoothPowerW: Double = 0.0
    var latestMassRateKgPerS: Double = 0.0
    var totalEventsWeighted: Double = 0.0
    var thresholdPassWeighted: Double = 0.0
    var pairEventsWeighted: Double = 0.0
    var eventRatePerS: Double = 0.0
    var pairRatePerS: Double = 0.0
    var fieldUniformity: Double = 0.0
    var radialContainment: Double = 0.0
    var stabilityIndex: Double = 0.0

    private var baseMatterCount: Int = 180
    private var baseAntiCount: Int = 180

    val channelGammaGamma = new ChannelAccumulator("双光子道")
    val channelGammaMeson = new ChannelAccumulator("γ+介子道")
    val channelMesonMeson = new ChannelAccumulator("介子主导道")

    val matterParticles: ArrayBuffer[VisualParticle] = ArrayBuffer.empty
    val antiParticles: ArrayBuffer[VisualParticle] = ArrayBuffer.empty
    val flashes: ArrayBuffer[Flash] = ArrayBuffer.empty
    val energyHistoryMJ: ArrayBuffer[Double] = ArrayBuffer.empty

    def setWorldBounds(width: Int, height: Int): Unit =
      worldWidth = max(240, width)
      worldHeight = max(240, height)

    def configureLive(
        confinementPct: Int,
        efficiencyPct: Int,
        reactivityPct: Int,
        speedPct: Int,
        fieldGradientPct: Int
    ): Unit =
      confinement = confinementPct / 100.0
      conversionEfficiency = efficiencyPct / 100.0
      reactionGain = 400.0 + reactivityPct * 40.0
      speedScale = 0.1 + (speedPct / 100.0) * 4.0
      fieldGradient = fieldGradientPct / 100.0

    def reset(matterMg: Int, antiMg: Int, particleBudget: Int): Unit =
      visualBudget = max(20, particleBudget)
      initialMatterKg = matterMg * 1e-6
      initialAntiKg = antiMg * 1e-6
      matterKg = initialMatterKg
      antiKg = initialAntiKg
      simTimeS = 0.0
      totalEnergyJ = 0.0
      instantPowerW = 0.0
      smoothPowerW = 0.0
      latestMassRateKgPerS = 0.0
      totalEventsWeighted = 0.0
      thresholdPassWeighted = 0.0
      pairEventsWeighted = 0.0
      eventRatePerS = 0.0
      pairRatePerS = 0.0
      fieldUniformity = 0.0
      radialContainment = 0.0
      stabilityIndex = 0.0
      channelGammaGamma.reset()
      channelGammaMeson.reset()
      channelMesonMeson.reset()
      flashes.clear()
      energyHistoryMJ.clear()
      seedParticleClouds()

    def step(dtReal: Double): Unit =
      val dt = dtReal * speedScale
      if dt <= 0.0 then return

      simTimeS += dt

      computeFieldStability()
      updateCloudMotion(matterParticles, dt, swirlSign = 1.0)
      updateCloudMotion(antiParticles, dt, swirlSign = -1.0)

      val effectiveConfinement = confinement * (0.5 + 0.5 * stabilityIndex)
      val kEff = reactionGain * (0.2 + 0.8 * effectiveConfinement * effectiveConfinement)
      val sideRate = kEff * matterKg * antiKg
      val dSide = min(min(matterKg, antiKg), sideRate * dt)

      matterKg -= dSide
      antiKg -= dSide

      val dMassTotal = 2.0 * dSide
      latestMassRateKgPerS = if dt > 0.0 then dMassTotal / dt else 0.0
      val dEnergy = dMassTotal * c * c * conversionEfficiency

      totalEnergyJ += dEnergy
      instantPowerW = if dt > 0.0 then dEnergy / dt else 0.0
      smoothPowerW = smoothPowerW * 0.86 + instantPowerW * 0.14

      simulateEventChannels(dMassTotal, dEnergy, dt)
      updateFlashes(dt)
      rebalanceParticleCounts()
      computeFieldStability()
      pushEnergyHistory()

      if matterKg < 1e-18 then matterKg = 0.0
      if antiKg < 1e-18 then antiKg = 0.0

    def runParameterScanAndExport(matterMg: Int, antiMg: Int, speedPct: Int): ScanSummary =
      val confinementGrid = Array(35, 55, 75, 92)
      val efficiencyGrid = Array(60, 75, 88, 96)
      val reactivityGrid = Array(12, 25, 45, 70)
      val fieldGradientGrid = Array(15, 35, 55, 75)

      val csv = new StringBuilder()
      csv.append(
        "id,matter_mg,anti_mg,confinement_pct,efficiency_pct,reactivity_pct,field_gradient_pct,speed_pct,total_energy_j,avg_power_w,stability_index,pair_events,threshold_events,gamma_gamma_share_pct,gamma_meson_share_pct,meson_share_pct\n"
      )

      var id = 1
      var bestEnergy = Double.MinValue
      var bestConfig = ""

      for
        conf <- confinementGrid
        eff <- efficiencyGrid
        react <- reactivityGrid
        field <- fieldGradientGrid
      do
        val metrics = simulateScenario(matterMg, antiMg, conf, eff, react, speedPct, field, seed = id * 97L + 17L)

        if metrics.totalEnergyJ > bestEnergy then
          bestEnergy = metrics.totalEnergyJ
          bestConfig = s"conf=$conf%, eff=$eff%, react=$react, field=$field%"

        csv.append(id).append(',')
          .append(matterMg).append(',')
          .append(antiMg).append(',')
          .append(conf).append(',')
          .append(eff).append(',')
          .append(react).append(',')
          .append(field).append(',')
          .append(speedPct).append(',')
          .append(csvNum(metrics.totalEnergyJ)).append(',')
          .append(csvNum(metrics.avgPowerW)).append(',')
          .append(csvNum(metrics.stabilityIndex)).append(',')
          .append(csvNum(metrics.pairEvents)).append(',')
          .append(csvNum(metrics.thresholdEvents)).append(',')
          .append(csvNum(metrics.gammaGammaSharePct)).append(',')
          .append(csvNum(metrics.gammaMesonSharePct)).append(',')
          .append(csvNum(metrics.mesonSharePct)).append('\n')

        id += 1

      val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
      val fileName = s"antimatter_scan_$stamp.csv"
      val outPath = Paths.get(System.getProperty("user.dir"), fileName)
      Files.writeString(outPath, csv.result(), StandardCharsets.UTF_8)

      ScanSummary(outPath.toString, id - 1, bestConfig, bestEnergy)

    def tntEquivalentTon: Double = totalEnergyJ / tntJPerTon
    def u235EquivalentKg: Double = totalEnergyJ / u235JPerKg
    def hiroshimaEquivalent: Double = totalEnergyJ / hiroshimaJ
    def pairProductionThresholdMeV: Double = pairThresholdMeV

    def gamma511EquivalentCount: Double =
      if photon511EnergyJ <= 0.0 then 0.0 else totalEnergyJ / photon511EnergyJ

    def theoreticalMaxEnergyJ: Double =
      2.0 * min(initialMatterKg, initialAntiKg) * c * c * conversionEfficiency

    def progressToTheoreticalPct: Double =
      if theoreticalMaxEnergyJ <= 0.0 then 0.0
      else min(100.0, (totalEnergyJ / theoreticalMaxEnergyJ) * 100.0)

    def matterUtilizationPct: Double =
      if initialMatterKg <= 0.0 then 0.0
      else ((initialMatterKg - matterKg) / initialMatterKg) * 100.0

    def antimatterUtilizationPct: Double =
      if initialAntiKg <= 0.0 then 0.0
      else ((initialAntiKg - antiKg) / initialAntiKg) * 100.0

    def gammaGammaSharePct: Double = sharePct(channelGammaGamma.events)
    def gammaMesonSharePct: Double = sharePct(channelGammaMeson.events)
    def mesonSharePct: Double = sharePct(channelMesonMeson.events)

    def thresholdPassRatePct: Double =
      if totalEventsWeighted <= 0.0 then 0.0
      else (thresholdPassWeighted / totalEventsWeighted) * 100.0

    def pairConversionRatePct: Double =
      if thresholdPassWeighted <= 0.0 then 0.0
      else (pairEventsWeighted / thresholdPassWeighted) * 100.0

    private def simulateScenario(
        matterMg: Int,
        antiMg: Int,
        confinementPct: Int,
        efficiencyPct: Int,
        reactivityPct: Int,
        speedPct: Int,
        fieldGradientPct: Int,
        seed: Long
    ): ScenarioMetrics =
      var mKg = matterMg * 1e-6
      var aKg = antiMg * 1e-6
      val conf = confinementPct / 100.0
      val eff = efficiencyPct / 100.0
      val react = 400.0 + reactivityPct * 40.0
      val speed = 0.1 + (speedPct / 100.0) * 4.0
      val gradient = fieldGradientPct / 100.0
      val rand = new Random(seed)

      val dt = 0.014 * speed
      val steps = 320

      var totalEnergy = 0.0
      var pairEvents = 0.0
      var thresholdEvents = 0.0
      var allEvents = 0.0
      var ggEvents = 0.0
      var gmEvents = 0.0
      var mmEvents = 0.0
      var stabilitySum = 0.0

      var i = 0
      while i < steps && mKg > 0.0 && aKg > 0.0 do
        val t = i * dt
        val uniformity = clamp(0.0, 1.0, 1.0 - 0.52 * gradient + 0.15 * cos(t * 1.9))
        val containment = clamp(0.0, 1.0, 0.52 + 0.42 * conf - 0.12 * gradient + (rand.nextDouble() - 0.5) * 0.08)
        val stability = clamp(0.0, 1.0, 0.6 * uniformity + 0.4 * containment)
        stabilitySum += stability

        val kEff = react * (0.2 + 0.8 * conf * stability)
        val dSide = min(min(mKg, aKg), kEff * mKg * aKg * dt)
        mKg -= dSide
        aKg -= dSide

        val dMass = 2.0 * dSide
        val dEnergy = dMass * c * c * eff
        totalEnergy += dEnergy

        val stepEvents = dMass / pairMassKg
        allEvents += stepEvents

        val pGG = clamp(0.08, 0.9, 0.44 + 0.35 * stability - 0.12 * gradient)
        val pGM = clamp(0.05, 0.8, 0.36 + 0.16 * (1.0 - stability) + 0.15 * gradient)
        val pMM = max(0.04, 1.0 - pGG - pGM)
        val norm = pGG + pGM + pMM

        val localGG = stepEvents * (pGG / norm)
        val localGM = stepEvents * (pGM / norm)
        val localMM = stepEvents * (pMM / norm)

        ggEvents += localGG
        gmEvents += localGM
        mmEvents += localMM

        val stepThreshold = localGG * 0.03 + localGM * 0.58 + localMM * 0.84
        thresholdEvents += stepThreshold

        val pairProb = clamp(0.01, 0.95, 0.1 + 0.62 * conf * stability * (1.0 - 0.25 * gradient))
        pairEvents += stepThreshold * pairProb

        i += 1

      val simDuration = max(1e-6, i * dt)
      val avgPower = totalEnergy / simDuration
      val avgStability = if i > 0 then stabilitySum / i else 0.0

      val ggShare = if allEvents <= 0.0 then 0.0 else (ggEvents / allEvents) * 100.0
      val gmShare = if allEvents <= 0.0 then 0.0 else (gmEvents / allEvents) * 100.0
      val mmShare = if allEvents <= 0.0 then 0.0 else (mmEvents / allEvents) * 100.0

      ScenarioMetrics(
        totalEnergyJ = totalEnergy,
        avgPowerW = avgPower,
        stabilityIndex = avgStability,
        pairEvents = pairEvents,
        thresholdEvents = thresholdEvents,
        gammaGammaSharePct = ggShare,
        gammaMesonSharePct = gmShare,
        mesonSharePct = mmShare
      )

    private def seedParticleClouds(): Unit =
      matterParticles.clear()
      antiParticles.clear()

      val total = max(20, visualBudget)
      val totalMass = initialMatterKg + initialAntiKg
      val matterFraction = if totalMass <= 0.0 then 0.5 else initialMatterKg / totalMass

      baseMatterCount = max(1, (total * matterFraction).round.toInt)
      baseAntiCount = max(1, total - baseMatterCount)

      var i = 0
      while i < baseMatterCount do
        matterParticles += spawnParticle(Species.Matter)
        i += 1

      var j = 0
      while j < baseAntiCount do
        antiParticles += spawnParticle(Species.Antimatter)
        j += 1

      computeFieldStability()

    private def spawnParticle(species: Species): VisualParticle =
      val margin = 30.0
      val x =
        if species == Species.Matter then
          margin + random.nextDouble() * (worldWidth * 0.42)
        else
          worldWidth * 0.58 + random.nextDouble() * (worldWidth * 0.42 - margin)

      val y = margin + random.nextDouble() * (worldHeight - margin * 2.0)
      val dirToCenter = if species == Species.Matter then 1.0 else -1.0
      val vx = dirToCenter * (35.0 + random.nextDouble() * 85.0)
      val vy = -36.0 + random.nextDouble() * 72.0
      new VisualParticle(species, Vec2(x, y), Vec2(vx, vy))

    private def updateCloudMotion(buffer: ArrayBuffer[VisualParticle], dt: Double, swirlSign: Double): Unit =
      val center = Vec2(worldWidth * 0.5, worldHeight * 0.5)
      val margin = 8.0
      val instability = 1.0 - stabilityIndex

      var i = 0
      while i < buffer.length do
        val p = buffer(i)
        val toCenter = center - p.pos
        val dist = max(1.0, toCenter.magnitude)
        val radial = toCenter / dist
        val tangent = Vec2(-radial.y * swirlSign, radial.x * swirlSign)
        val localField = fieldStrengthAt(p.pos, simTimeS)

        val pull = radial * (18.0 + 140.0 * localField / (1.0 + dist * 0.018))
        val swirl = tangent * (9.0 + 34.0 * (1.0 - localField))
        val jitterAmp = 8.0 + 26.0 * instability + 10.0 * fieldGradient
        val jitter = Vec2((random.nextDouble() - 0.5) * jitterAmp, (random.nextDouble() - 0.5) * jitterAmp)

        p.vel = p.vel + (pull + swirl + jitter) * dt
        val speed = p.vel.magnitude
        val speedLimit = 130.0 + 120.0 * localField
        if speed > speedLimit then p.vel = p.vel * (speedLimit / speed)

        p.pos = p.pos + p.vel * dt

        if p.pos.x < margin then
          p.pos = p.pos.copy(x = margin)
          p.vel = p.vel.copy(x = abs(p.vel.x))
        else if p.pos.x > worldWidth - margin then
          p.pos = p.pos.copy(x = worldWidth - margin)
          p.vel = p.vel.copy(x = -abs(p.vel.x))

        if p.pos.y < margin then
          p.pos = p.pos.copy(y = margin)
          p.vel = p.vel.copy(y = abs(p.vel.y))
        else if p.pos.y > worldHeight - margin then
          p.pos = p.pos.copy(y = worldHeight - margin)
          p.vel = p.vel.copy(y = -abs(p.vel.y))

        i += 1

    private def fieldStrengthAt(pos: Vec2, t: Double): Double =
      val nx = ((pos.x / worldWidth) - 0.5) * 2.0
      val ny = ((pos.y / worldHeight) - 0.5) * 2.0
      val radial = min(1.0, sqrt(nx * nx + ny * ny))
      val coreFalloff = 1.0 - fieldGradient * radial
      val ripple = 0.18 * fieldGradient * cos(nx * Pi * 2.5 + t * 1.2) * sin(ny * Pi * 2.0 - t * 1.4)
      clamp(0.06, 1.0, confinement * (0.7 + 0.3 * coreFalloff + ripple))

    private def computeFieldStability(): Unit =
      val grid = 6
      var sum = 0.0
      var sumSq = 0.0
      var n = 0

      var gx = 0
      while gx < grid do
        var gy = 0
        while gy < grid do
          val px = (gx + 0.5) / grid.toDouble * worldWidth
          val py = (gy + 0.5) / grid.toDouble * worldHeight
          val b = fieldStrengthAt(Vec2(px, py), simTimeS)
          sum += b
          sumSq += b * b
          n += 1
          gy += 1
        gx += 1

      val mean = if n > 0 then sum / n else 0.0
      val variance = max(0.0, (sumSq / max(1, n)) - mean * mean)
      val std = sqrt(variance)
      fieldUniformity = clamp(0.0, 1.0, 1.0 - std / (mean + 1e-6))

      val center = Vec2(worldWidth * 0.5, worldHeight * 0.5)
      val maxDist = sqrt(center.x * center.x + center.y * center.y)
      val allCount = matterParticles.length + antiParticles.length

      if allCount <= 0 then
        radialContainment = 0.0
      else
        var distSum = 0.0
        matterParticles.foreach(p => distSum += (p.pos - center).magnitude)
        antiParticles.foreach(p => distSum += (p.pos - center).magnitude)
        val avgNorm = (distSum / allCount) / max(1.0, maxDist)
        radialContainment = clamp(0.0, 1.0, 1.0 - avgNorm)

      stabilityIndex = clamp(0.02, 1.0, 0.62 * fieldUniformity + 0.38 * radialContainment)

    private def pickChannel(): Channel =
      val pGGBase = 0.40 + 0.45 * stabilityIndex * (1.0 - 0.4 * fieldGradient)
      val pGMBase = 0.34 + 0.15 * (1.0 - stabilityIndex) + 0.20 * fieldGradient
      val pMMBase = max(0.08, 1.0 - pGGBase - pGMBase)

      val sum = pGGBase + pGMBase + pMMBase
      val pGG = pGGBase / sum
      val pGM = pGMBase / sum

      val r = random.nextDouble()
      if r < pGG then Channel.GammaGamma
      else if r < pGG + pGM then Channel.GammaMeson
      else Channel.MesonMeson

    private def samplePhotonEnergyMeV(channel: Channel): Double =
      channel match
        case Channel.GammaGamma => 0.45 + random.nextDouble() * 0.18
        case Channel.GammaMeson => 0.60 + random.nextDouble() * 4.6
        case Channel.MesonMeson => 1.00 + random.nextDouble() * 15.0

    private def simulateEventChannels(dMassTotal: Double, dEnergy: Double, dt: Double): Unit =
      if dMassTotal <= 0.0 || dEnergy <= 0.0 || dt <= 0.0 then return

      val physicalEvents = dMassTotal / pairMassKg
      if physicalEvents <= 0.0 then return

      val sampleEvents = min(220, max(18, (sqrt(physicalEvents) * 0.002).toInt + 12))
      val eventWeight = physicalEvents / sampleEvents.toDouble

      var ggCount = 0
      var gmCount = 0
      var mmCount = 0
      var thresholdPass = 0
      var pairCount = 0

      var i = 0
      while i < sampleEvents do
        val channel = pickChannel()

        channel match
          case Channel.GammaGamma => ggCount += 1
          case Channel.GammaMeson => gmCount += 1
          case Channel.MesonMeson => mmCount += 1

        val photonEnergyMeV = samplePhotonEnergyMeV(channel)
        if photonEnergyMeV >= pairThresholdMeV then
          thresholdPass += 1
          val thresholdFactor = photonEnergyMeV / (photonEnergyMeV + pairThresholdMeV)
          val pairProb = clamp(
            0.01,
            0.92,
            0.08 + 0.68 * confinement * stabilityIndex * thresholdFactor * (1.0 - 0.3 * fieldGradient)
          )
          if random.nextDouble() < pairProb then pairCount += 1

        i += 1

      val ggEvents = ggCount * eventWeight
      val gmEvents = gmCount * eventWeight
      val mmEvents = mmCount * eventWeight

      val ggEnergy = dEnergy * (ggCount.toDouble / sampleEvents)
      val gmEnergy = dEnergy * (gmCount.toDouble / sampleEvents)
      val mmEnergy = dEnergy * (mmCount.toDouble / sampleEvents)

      channelGammaGamma.events += ggEvents
      channelGammaGamma.energyJ += ggEnergy
      channelGammaMeson.events += gmEvents
      channelGammaMeson.energyJ += gmEnergy
      channelMesonMeson.events += mmEvents
      channelMesonMeson.energyJ += mmEnergy

      totalEventsWeighted += physicalEvents
      thresholdPassWeighted += thresholdPass * eventWeight
      pairEventsWeighted += pairCount * eventWeight
      eventRatePerS = physicalEvents / dt
      pairRatePerS = (pairCount * eventWeight) / dt

      spawnFlashesByChannel(ggEnergy, gmEnergy, mmEnergy)

    private def spawnFlashesByChannel(ggEnergy: Double, gmEnergy: Double, mmEnergy: Double): Unit =
      spawnChannelFlashes(ggEnergy, new Color(120, 230, 255), 7.0, 20.0)
      spawnChannelFlashes(gmEnergy, new Color(255, 190, 90), 9.0, 24.0)
      spawnChannelFlashes(mmEnergy, new Color(255, 110, 170), 11.0, 28.0)

    private def spawnChannelFlashes(energy: Double, color: Color, minR: Double, maxR: Double): Unit =
      if energy <= 0.0 then return

      val expected = energy / 7.0e9
      val whole = expected.toInt
      val frac = expected - whole
      val bonus = if random.nextDouble() < frac then 1 else 0
      val count = min(18, max(1, whole + bonus + 1))

      val center = Vec2(worldWidth * 0.5, worldHeight * 0.5)
      var i = 0
      while i < count do
        val angle = random.nextDouble() * 2.0 * Pi
        val r = random.nextDouble() * 85.0
        val px = center.x + cos(angle) * r
        val py = center.y + sin(angle) * r
        val life = 0.42 + random.nextDouble() * 0.42
        val baseRadius = minR + random.nextDouble() * (maxR - minR)
        flashes += new Flash(Vec2(px, py), baseRadius, life, energy / count, color)
        i += 1

    private def updateFlashes(dt: Double): Unit =
      flashes.foreach(f => f.age += dt)
      flashes.filterInPlace(f => f.age <= f.life)

    private def rebalanceParticleCounts(): Unit =
      val targetMatter =
        if initialMatterKg <= 0.0 then 0
        else max(0, ((matterKg / initialMatterKg) * baseMatterCount).round.toInt)
      val targetAnti =
        if initialAntiKg <= 0.0 then 0
        else max(0, ((antiKg / initialAntiKg) * baseAntiCount).round.toInt)

      adjustParticleBuffer(matterParticles, targetMatter, Species.Matter)
      adjustParticleBuffer(antiParticles, targetAnti, Species.Antimatter)

    private def adjustParticleBuffer(
        buffer: ArrayBuffer[VisualParticle],
        target: Int,
        species: Species
    ): Unit =
      if buffer.length > target then
        buffer.remove(target, buffer.length - target)
      else
        var i = buffer.length
        while i < target do
          buffer += spawnParticle(species)
          i += 1

    private def pushEnergyHistory(): Unit =
      energyHistoryMJ += totalEnergyJ / 1.0e6
      val maxLen = 260
      if energyHistoryMJ.length > maxLen then
        energyHistoryMJ.remove(0, energyHistoryMJ.length - maxLen)

    private def sharePct(value: Double): Double =
      if totalEventsWeighted <= 0.0 then 0.0
      else (value / totalEventsWeighted) * 100.0

    private def clamp(minV: Double, maxV: Double, value: Double): Double =
      max(minV, min(maxV, value))

    private def csvNum(value: Double): String =
      String.format(Locale.US, "%.6e", java.lang.Double.valueOf(value))

  private final class ReactorPanel(model: AntimatterModel) extends JPanel:
    setPreferredSize(new Dimension(model.worldWidth, model.worldHeight))
    setBackground(new Color(8, 10, 20))
    setToolTipText("")
    ToolTipManager.sharedInstance().registerComponent(this)

    private var running: Boolean = false
    private var hoverPaused: Boolean = false
    private var frameCallback: () => Unit = () => ()

    private final case class HoverTarget(title: String, detailLines: List[String])
    private var currentHover: Option[HoverTarget] = None

    private val timer = new Timer(16, new ActionListener:
      override def actionPerformed(e: ActionEvent): Unit =
        model.setWorldBounds(max(240, getWidth), max(240, getHeight))
        if running && !hoverPaused then
          var i = 0
          while i < 3 do
            model.step(0.012)
            i += 1
        repaint()
        frameCallback()
    )
    timer.start()

    addMouseMotionListener(new MouseMotionAdapter:
      override def mouseMoved(e: MouseEvent): Unit =
        updateHoverState(e.getX, e.getY)
    )

    addMouseListener(new MouseAdapter:
      override def mouseExited(e: MouseEvent): Unit =
        clearHoverState()
    )

    def isRunning: Boolean = running
    def setRunning(value: Boolean): Unit = running = value
    def setFrameCallback(cb: () => Unit): Unit = frameCallback = cb

    override def paintComponent(g: Graphics): Unit =
      super.paintComponent(g)
      val g2 = g.asInstanceOf[Graphics2D]
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

      drawBackground(g2)
      drawReactorCore(g2)
      drawParticles(g2, model.matterParticles, new Color(70, 180, 255), 4)
      drawParticles(g2, model.antiParticles, new Color(255, 120, 70), 4)
      drawFlashes(g2)
      drawEnergyChart(g2)
      drawHud(g2)

    override def getToolTipText(event: MouseEvent): String =
      detectHoverTarget(event.getX, event.getY).map(renderTooltip).orNull

    private def updateHoverState(mx: Int, my: Int): Unit =
      val detected = detectHoverTarget(mx, my)
      if detected != currentHover then
        currentHover = detected
        hoverPaused = detected.nonEmpty
        if hoverPaused && running then
          setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
        else
          setCursor(Cursor.getDefaultCursor)
        repaint()

    private def clearHoverState(): Unit =
      currentHover = None
      hoverPaused = false
      setCursor(Cursor.getDefaultCursor)
      repaint()

    private def detectHoverTarget(mx: Int, my: Int): Option[HoverTarget] =
      detectParticleHover(mx, my, model.matterParticles, "物质粒子", model.matterKg)
        .orElse(detectParticleHover(mx, my, model.antiParticles, "反物质粒子", model.antiKg))
        .orElse(detectFlashHover(mx, my))
        .orElse(detectCoreHover(mx, my))
        .orElse(detectChartHover(mx, my))

    private def detectParticleHover(
        mx: Int,
        my: Int,
        particles: ArrayBuffer[VisualParticle],
        name: String,
        cloudMassKg: Double
    ): Option[HoverTarget] =
      if particles.isEmpty then return None

      var nearest: VisualParticle = particles.head
      var bestDist2 = Double.MaxValue

      particles.foreach { p =>
        val dx = p.pos.x - mx
        val dy = p.pos.y - my
        val d2 = dx * dx + dy * dy
        if d2 < bestDist2 then
          bestDist2 = d2
          nearest = p
      }

      val hitRadius = 11.0
      if bestDist2 > hitRadius * hitRadius then return None

      val speed = nearest.vel.magnitude
      val cloudMassMg = cloudMassKg * 1e6

      Some(
        HoverTarget(
          title = name,
          detailLines = List(
            f"位置: x=${nearest.pos.x}%.1f, y=${nearest.pos.y}%.1f",
            f"速度: ${speed}%.2f px/s",
            s"云团粒子数: ${particles.length}",
            f"云团剩余质量: ${cloudMassMg}%.6f mg"
          )
        )
      )

    private def detectFlashHover(mx: Int, my: Int): Option[HoverTarget] =
      flashes.find { f =>
        val t = min(1.0, f.age / f.life)
        val radius = f.baseRadius + 42.0 * t
        val dx = f.pos.x - mx
        val dy = f.pos.y - my
        val dist = sqrt(dx * dx + dy * dy)
        dist <= radius + 6.0
      }.map { f =>
        val channel = flashChannelName(f.color)
        val remaining = max(0.0, f.life - f.age)
        HoverTarget(
          title = s"湮灭闪光 ($channel)",
          detailLines = List(
            f"瞬时能量: ${f.energyJ / 1.0e6}%.4f MJ",
            f"剩余寿命: ${remaining}%.3f s",
            "说明: 颜色对应不同反应道"
          )
        )
      }

    private def flashChannelName(color: Color): String =
      if color.getBlue > 200 && color.getGreen > 200 then "双光子道"
      else if color.getRed > 240 && color.getGreen > 150 then "γ+介子道"
      else "介子主导道"

    private def detectCoreHover(mx: Int, my: Int): Option[HoverTarget] =
      val cx = getWidth * 0.5
      val cy = getHeight * 0.5
      val dx = mx - cx
      val dy = my - cy
      val dist = sqrt(dx * dx + dy * dy)

      if dist > 128.0 then return None

      Some(
        HoverTarget(
          title = "磁约束反应核心",
          detailLines = List(
            f"场均匀度: ${model.fieldUniformity * 100.0}%.2f%%",
            f"径向约束: ${model.radialContainment * 100.0}%.2f%%",
            f"综合稳定度: ${model.stabilityIndex * 100.0}%.2f%%"
          )
        )
      )

    private def detectChartHover(mx: Int, my: Int): Option[HoverTarget] =
      val (chartX, chartY, chartW, chartH) = energyChartBounds()
      val inside = mx >= chartX && mx <= chartX + chartW && my >= chartY && my <= chartY + chartH
      if !inside then return None

      Some(
        HoverTarget(
          title = "累计能量曲线",
          detailLines = List(
            f"当前累计能量: ${model.totalEnergyJ / 1.0e6}%.3f MJ",
            f"平滑平均功率: ${model.smoothPowerW / 1.0e6}%.3f MW",
            s"采样点数: ${model.energyHistoryMJ.length}"
          )
        )
      )

    private def renderTooltip(target: HoverTarget): String =
      val lines = target.detailLines.mkString("<br/>")
      s"<html><b>${target.title}</b><br/>$lines<br/><i>悬停时仿真已临时暂停</i></html>"

    private def drawBackground(g2: Graphics2D): Unit =
      val w = getWidth
      val h = getHeight
      val grad = new GradientPaint(0, 0, new Color(8, 10, 20), 0, h, new Color(14, 28, 46))
      g2.setPaint(grad)
      g2.fillRect(0, 0, w, h)

      g2.setColor(new Color(255, 255, 255, 20))
      var i = 0
      while i < 180 do
        val x = (i * 73) % max(1, w)
        val y = (i * 41) % max(1, h)
        g2.fillRect(x, y, 2, 2)
        i += 1

    private def drawReactorCore(g2: Graphics2D): Unit =
      val cx = getWidth / 2
      val cy = getHeight / 2
      val stability = model.stabilityIndex
      val ringRed = (220.0 * (1.0 - stability) + 45.0).toInt
      val ringGreen = (120.0 + 120.0 * stability).toInt

      g2.setColor(new Color(255, 210, 70, 45))
      g2.fillOval(cx - 120, cy - 120, 240, 240)

      g2.setColor(new Color(ringRed, ringGreen, 80, 145))
      g2.setStroke(new BasicStroke(2.2f))
      g2.drawOval(cx - 92, cy - 92, 184, 184)

      g2.setColor(new Color(255, 235, 180, 100))
      g2.drawOval(cx - 56, cy - 56, 112, 112)

    private def drawParticles(
        g2: Graphics2D,
        particles: ArrayBuffer[VisualParticle],
        color: Color,
        radius: Int
    ): Unit =
      particles.foreach { p =>
        val x = p.pos.x.toInt
        val y = p.pos.y.toInt
        g2.setColor(new Color(color.getRed, color.getGreen, color.getBlue, 80))
        g2.fillOval(x - radius - 2, y - radius - 2, (radius + 2) * 2, (radius + 2) * 2)
        g2.setColor(color)
        g2.fillOval(x - radius, y - radius, radius * 2, radius * 2)
      }

    private def drawFlashes(g2: Graphics2D): Unit =
      flashes.foreach { f =>
        val t = min(1.0, f.age / f.life)
        val alpha = (220.0 * (1.0 - t)).toInt
        val radius = (f.baseRadius + 42.0 * t).toInt
        g2.setColor(new Color(f.color.getRed, f.color.getGreen, f.color.getBlue, max(0, alpha)))
        g2.setStroke(new BasicStroke(2.0f))
        g2.drawOval(f.pos.x.toInt - radius, f.pos.y.toInt - radius, radius * 2, radius * 2)
      }

    private def energyChartBounds(): (Int, Int, Int, Int) =
      val chartX = 24
      val chartW = min(440, max(280, getWidth / 3))
      val chartH = 120
      val chartY = getHeight - chartH - 28
      (chartX, chartY, chartW, chartH)

    private def drawEnergyChart(g2: Graphics2D): Unit =
      val history = model.energyHistoryMJ
      if history.length < 2 then return

      val (chartX, chartY, chartW, chartH) = energyChartBounds()

      g2.setColor(new Color(0, 0, 0, 90))
      g2.fillRoundRect(chartX, chartY, chartW, chartH, 12, 12)

      g2.setColor(new Color(220, 240, 255, 130))
      g2.setStroke(new BasicStroke(1.0f))
      g2.drawRoundRect(chartX, chartY, chartW, chartH, 12, 12)

      val yMax = max(1.0, history.max)
      val dx = chartW.toDouble / (history.length - 1)
      g2.setColor(new Color(120, 230, 255, 220))
      g2.setStroke(new BasicStroke(2.0f))

      var i = 1
      while i < history.length do
        val x1 = chartX + ((i - 1) * dx).toInt
        val x2 = chartX + (i * dx).toInt
        val y1 = chartY + chartH - ((history(i - 1) / yMax) * chartH).toInt
        val y2 = chartY + chartH - ((history(i) / yMax) * chartH).toInt
        g2.drawLine(x1, y1, x2, y2)
        i += 1

      g2.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12))
      g2.setColor(new Color(230, 245, 255))
      g2.drawString(f"累计能量 (MJ)，峰值 ${yMax}%.2f", chartX + 12, chartY + 18)

    private def drawHud(g2: Graphics2D): Unit =
      g2.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14))
      g2.setColor(new Color(230, 240, 255))
      g2.drawString(f"场稳定度: ${model.stabilityIndex * 100.0}%.1f%%", 26, 32)
      g2.drawString(f"双光子道占比: ${model.gammaGammaSharePct}%.1f%%", 26, 54)
      g2.drawString(f"电子对产生率: ${model.pairConversionRatePct}%.1f%%", 26, 76)

    private def flashes: ArrayBuffer[Flash] = model.flashes

  private final class AntimatterFrame extends JFrame("Scala GUI 反物质湮灭模拟器"):
    private val model = new AntimatterModel(980, 700)
    private val reactorPanel = new ReactorPanel(model)

    private val matterSlider = new JSlider(1, 1000, 300)
    private val antiSlider = new JSlider(1, 1000, 300)
    private val particleSlider = new JSlider(80, 800, 360)
    private val confinementSlider = new JSlider(1, 100, 65)
    private val fieldGradientSlider = new JSlider(1, 100, 35)
    private val efficiencySlider = new JSlider(10, 100, 88)
    private val reactivitySlider = new JSlider(1, 100, 20)
    private val speedSlider = new JSlider(1, 100, 30)

    private val matterValue = new JLabel("300 mg")
    private val antiValue = new JLabel("300 mg")
    private val particleValue = new JLabel("360")
    private val confinementValue = new JLabel("65%")
    private val fieldGradientValue = new JLabel("35%")
    private val efficiencyValue = new JLabel("88%")
    private val reactivityValue = new JLabel("20")
    private val speedValue = new JLabel("30")

    private val runButton = new JButton("开始")
    private val resetButton = new JButton("重置")
    private val scanButton = new JButton("参数扫描导出 CSV")

    private val energyKpi = new JLabel("--", SwingConstants.CENTER)
    private val powerKpi = new JLabel("--", SwingConstants.CENTER)
    private val stabilityKpi = new JLabel("--", SwingConstants.CENTER)
    private val pairRateKpi = new JLabel("--", SwingConstants.CENTER)

    private val statusArea = new JTextArea(10, 24)
    private val statusScroll = new JScrollPane(statusArea)
    private var scanStatus: String = "尚未执行参数扫描"
    private var statusAutoFollowBottom: Boolean = true
    private var suppressStatusAdjustEvent: Boolean = false
    private var lastStatusText: String = ""
    private var lastStatusRefreshMs: Long = 0L

    initUi()

    private def initUi(): Unit =
      setLayout(new BorderLayout())
      setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)

      add(reactorPanel, BorderLayout.CENTER)
      add(buildControlPanel(), BorderLayout.EAST)

      setMinimumSize(new Dimension(1180, 700))
      setLocationRelativeTo(null)

      applyStaticTooltips()
      hookEvents()
      applyLiveParameters()
      doReset()

      reactorPanel.setFrameCallback(() => refreshStatus())

    private def buildControlPanel(): JPanel =
      val panel = new JPanel()
      panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
      panel.setPreferredSize(new Dimension(390, 0))
      panel.setMinimumSize(new Dimension(340, 0))
      panel.setBorder(new EmptyBorder(12, 12, 12, 12))
      panel.setBackground(new Color(242, 247, 255))

      val title = new JLabel("反物质湮灭重要数据面板")
      title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 18))
      panel.add(title)

      val subtitle = new JLabel("精简视图：只展示关键指标")
      subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13))
      subtitle.setBorder(new EmptyBorder(4, 0, 8, 0))
      panel.add(subtitle)

      panel.add(buildKpiPanel())
      panel.add(sliderBlock("初始物质量", matterSlider, matterValue))
      panel.add(sliderBlock("初始反物质量", antiSlider, antiValue))
      panel.add(sliderBlock("可视化粒子数", particleSlider, particleValue))
      panel.add(sliderBlock("磁场约束强度", confinementSlider, confinementValue))
      panel.add(sliderBlock("场分布梯度", fieldGradientSlider, fieldGradientValue))
      panel.add(sliderBlock("能量转换效率", efficiencySlider, efficiencyValue))
      panel.add(sliderBlock("反应系数 k", reactivitySlider, reactivityValue))
      panel.add(sliderBlock("仿真速度", speedSlider, speedValue))

      val buttonRow = new JPanel()
      buttonRow.setOpaque(false)
      buttonRow.add(runButton)
      buttonRow.add(resetButton)
      buttonRow.setBorder(new EmptyBorder(10, 0, 4, 0))
      panel.add(buttonRow)

      val exportRow = new JPanel(new BorderLayout())
      exportRow.setOpaque(false)
      exportRow.setBorder(new EmptyBorder(4, 0, 8, 0))
      exportRow.add(scanButton, BorderLayout.CENTER)
      panel.add(exportRow)

      statusArea.setLineWrap(true)
      statusArea.setWrapStyleWord(true)
      statusArea.setEditable(false)
      statusArea.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12))
      statusArea.setBackground(new Color(250, 253, 255))
      statusArea.setBorder(new EmptyBorder(10, 10, 10, 10))

      statusScroll.setBorder(new LineBorder(new Color(215, 225, 240)))
      statusScroll.getVerticalScrollBar.setUnitIncrement(16)
      statusScroll.getVerticalScrollBar.addAdjustmentListener(new java.awt.event.AdjustmentListener:
        override def adjustmentValueChanged(e: java.awt.event.AdjustmentEvent): Unit =
          if !suppressStatusAdjustEvent then
            val bar = statusScroll.getVerticalScrollBar
            statusAutoFollowBottom = bar.getValue + bar.getVisibleAmount() >= bar.getMaximum - 8
      )
      panel.add(statusScroll)

      panel

    private def buildKpiPanel(): JPanel =
      val panel = new JPanel(new GridLayout(2, 2, 8, 8))
      panel.setOpaque(false)
      panel.setBorder(new EmptyBorder(4, 0, 10, 0))

      panel.add(metricCard("累计能量", energyKpi, new Color(96, 170, 255)))
      panel.add(metricCard("平滑功率", powerKpi, new Color(80, 205, 160)))
      panel.add(metricCard("场稳定度", stabilityKpi, new Color(255, 185, 85)))
      panel.add(metricCard("电子对速率", pairRateKpi, new Color(245, 130, 160)))
      panel

    private def metricCard(title: String, valueLabel: JLabel, accent: Color): JPanel =
      val card = new JPanel(new BorderLayout())
      card.setBackground(new Color(252, 254, 255))
      card.setBorder(new LineBorder(new Color(accent.getRed, accent.getGreen, accent.getBlue, 120), 1, true))

      val titleLabel = new JLabel(title, SwingConstants.CENTER)
      titleLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11))
      titleLabel.setForeground(new Color(62, 74, 98))
      card.add(titleLabel, BorderLayout.NORTH)

      valueLabel.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 13))
      valueLabel.setForeground(new Color(28, 44, 78))
      card.add(valueLabel, BorderLayout.CENTER)

      card

    private def applyStaticTooltips(): Unit =
      reactorPanel.setToolTipText("将鼠标悬停在粒子/闪光/核心/曲线区域可查看详情并临时暂停")

      matterSlider.setToolTipText("设置初始物质量，单位 mg")
      antiSlider.setToolTipText("设置初始反物质量，单位 mg")
      particleSlider.setToolTipText("设置可视化粒子数量，影响画面密度")
      confinementSlider.setToolTipText("磁场约束强度，影响粒子聚束能力")
      fieldGradientSlider.setToolTipText("场分布梯度，值越高空间不均匀性越明显")
      efficiencySlider.setToolTipText("质量转化为可用能量的效率")
      reactivitySlider.setToolTipText("反应系数 k，影响质量消耗速度")
      speedSlider.setToolTipText("仿真时间推进速度")

      runButton.setToolTipText("开始或暂停仿真")
      resetButton.setToolTipText("按当前参数重置系统状态")
      scanButton.setToolTipText("批量扫描参数组合并导出 CSV 结果")

      energyKpi.setToolTipText("累计释放能量")
      powerKpi.setToolTipText("平滑平均功率")
      stabilityKpi.setToolTipText("磁约束综合稳定度")
      pairRateKpi.setToolTipText("单位时间电子对产生事件率")

      statusArea.setToolTipText("详细状态区：当你手动滚动到上方时会暂停自动跟随")

    private def sliderBlock(title: String, slider: JSlider, valueLabel: JLabel): JPanel =
      slider.setPaintTicks(false)
      slider.setOpaque(false)

      val row = new JPanel(new BorderLayout())
      row.setOpaque(false)
      row.setBorder(new EmptyBorder(4, 0, 4, 0))

      val top = new JPanel(new BorderLayout())
      top.setOpaque(false)
      val label = new JLabel(title)
      label.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12))
      valueLabel.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12))
      top.add(label, BorderLayout.WEST)
      top.add(valueLabel, BorderLayout.EAST)

      row.add(top, BorderLayout.NORTH)
      row.add(slider, BorderLayout.SOUTH)
      row

    private def hookEvents(): Unit =
      runButton.addActionListener(new ActionListener:
        override def actionPerformed(e: ActionEvent): Unit =
          if reactorPanel.isRunning then
            reactorPanel.setRunning(false)
            runButton.setText("开始")
          else
            reactorPanel.setRunning(true)
            runButton.setText("暂停")
      )

      resetButton.addActionListener(new ActionListener:
        override def actionPerformed(e: ActionEvent): Unit =
          doReset()
      )

      scanButton.addActionListener(new ActionListener:
        override def actionPerformed(e: ActionEvent): Unit =
          scanButton.setEnabled(false)
          scanButton.setText("扫描中...")
          scanStatus = "参数扫描进行中..."
          refreshStatus(forceTextUpdate = true)

          val m = matterSlider.getValue
          val a = antiSlider.getValue
          val speed = speedSlider.getValue

          val worker = new Thread(new Runnable:
            override def run(): Unit =
              try
                val summary = model.runParameterScanAndExport(m, a, speed)
                val fileName = Paths.get(summary.filePath).getFileName.toString
                val bestMJ = summary.bestEnergyJ / 1.0e6

                SwingUtilities.invokeLater(new Runnable:
                  override def run(): Unit =
                    scanStatus = f"已导出 $fileName，样本 ${summary.rowCount} 组，最优 ${bestMJ}%.3f MJ (${summary.bestConfig})"
                    scanButton.setEnabled(true)
                    scanButton.setText("参数扫描导出 CSV")
                    refreshStatus(forceTextUpdate = true)
                )
              catch
                case ex: Exception =>
                  SwingUtilities.invokeLater(new Runnable:
                    override def run(): Unit =
                      scanStatus = s"扫描导出失败: ${ex.getMessage}"
                      scanButton.setEnabled(true)
                      scanButton.setText("参数扫描导出 CSV")
                      refreshStatus(forceTextUpdate = true)
                  )
          )

          worker.setDaemon(true)
          worker.start()
      )

      val valueListener = new ChangeListener:
        override def stateChanged(e: javax.swing.event.ChangeEvent): Unit =
          refreshSliderLabels()
          applyLiveParameters()
          if !reactorPanel.isRunning then refreshStatus(forceTextUpdate = true)

      matterSlider.addChangeListener(valueListener)
      antiSlider.addChangeListener(valueListener)
      particleSlider.addChangeListener(valueListener)
      confinementSlider.addChangeListener(valueListener)
      fieldGradientSlider.addChangeListener(valueListener)
      efficiencySlider.addChangeListener(valueListener)
      reactivitySlider.addChangeListener(valueListener)
      speedSlider.addChangeListener(valueListener)

    private def applyLiveParameters(): Unit =
      model.configureLive(
        confinementSlider.getValue,
        efficiencySlider.getValue,
        reactivitySlider.getValue,
        speedSlider.getValue,
        fieldGradientSlider.getValue
      )

    private def doReset(): Unit =
      applyLiveParameters()
      model.reset(matterSlider.getValue, antiSlider.getValue, particleSlider.getValue)
      reactorPanel.repaint()
      refreshStatus(forceTextUpdate = true)

    private def refreshSliderLabels(): Unit =
      matterValue.setText(s"${matterSlider.getValue} mg")
      antiValue.setText(s"${antiSlider.getValue} mg")
      particleValue.setText(s"${particleSlider.getValue}")
      confinementValue.setText(s"${confinementSlider.getValue}%")
      fieldGradientValue.setText(s"${fieldGradientSlider.getValue}%")
      efficiencyValue.setText(s"${efficiencySlider.getValue}%")
      reactivityValue.setText(s"${reactivitySlider.getValue}")
      speedValue.setText(s"${speedSlider.getValue}")

    private def formatEnergy(valueJ: Double): String =
      if valueJ >= 1.0e12 then f"${valueJ / 1.0e12}%.3f TJ"
      else if valueJ >= 1.0e9 then f"${valueJ / 1.0e9}%.3f GJ"
      else if valueJ >= 1.0e6 then f"${valueJ / 1.0e6}%.3f MJ"
      else f"${valueJ}%.3f J"

    private def formatPower(valueW: Double): String =
      if valueW >= 1.0e12 then f"${valueW / 1.0e12}%.3f TW"
      else if valueW >= 1.0e9 then f"${valueW / 1.0e9}%.3f GW"
      else if valueW >= 1.0e6 then f"${valueW / 1.0e6}%.3f MW"
      else if valueW >= 1.0e3 then f"${valueW / 1.0e3}%.3f kW"
      else f"${valueW}%.3f W"

    private def formatRate(valuePerS: Double): String =
      if valuePerS >= 1.0e12 then f"${valuePerS / 1.0e12}%.3f T"
      else if valuePerS >= 1.0e9 then f"${valuePerS / 1.0e9}%.3f G"
      else if valuePerS >= 1.0e6 then f"${valuePerS / 1.0e6}%.3f M"
      else if valuePerS >= 1.0e3 then f"${valuePerS / 1.0e3}%.3f K"
      else f"${valuePerS}%.3f"

    private def refreshStatus(forceTextUpdate: Boolean = false): Unit =
      val sci = new DecimalFormat("0.000E0")
      val dec = new DecimalFormat("0.000")
      val now = System.currentTimeMillis()

      val energyText = formatEnergy(model.totalEnergyJ)
      val powerText = formatPower(model.smoothPowerW)
      val pairRateText = s"${formatRate(model.pairRatePerS)}/s"

      energyKpi.setText(energyText)
      powerKpi.setText(powerText)
      stabilityKpi.setText(f"${model.stabilityIndex * 100.0}%.1f%%")
      pairRateKpi.setText(pairRateText)

      val newStatusText =
        "关键仿真数据\n" +
          s"时间: ${dec.format(model.simTimeS)} s\n" +
          s"质量变化率: ${dec.format(model.latestMassRateKgPerS * 1e6)} mg/s\n" +
          s"剩余质量: 物质 ${dec.format(model.matterKg * 1e6)} mg | 反物质 ${dec.format(model.antiKg * 1e6)} mg\n" +
          s"理论上限完成度: ${dec.format(model.progressToTheoreticalPct)} %\n" +
          s"场均匀度/径向约束/稳定度: ${dec.format(model.fieldUniformity * 100)}% / ${dec.format(model.radialContainment * 100)}% / ${dec.format(model.stabilityIndex * 100)}%\n" +
          s"阈值通过率: ${dec.format(model.thresholdPassRatePct)} % | 对产生转化率: ${dec.format(model.pairConversionRatePct)} %\n" +
          s"通道占比: 双光子 ${dec.format(model.gammaGammaSharePct)}% | γ+介子 ${dec.format(model.gammaMesonSharePct)}% | 介子主导 ${dec.format(model.mesonSharePct)}%\n" +
          s"事件速率: ${formatRate(model.eventRatePerS)}/s | 电子对速率: $pairRateText\n" +
          s"当量: TNT ${sci.format(model.tntEquivalentTon)} 吨 | U-235 ${dec.format(model.u235EquivalentKg)} kg | 广岛 ${dec.format(model.hiroshimaEquivalent)} 倍\n" +
          s"511 keV 光子等效数: ${sci.format(model.gamma511EquivalentCount)}\n" +
          s"扫描状态: $scanStatus\n"

      val updateIntervalMs = if reactorPanel.isRunning then 130L else 0L
      val hitRefreshInterval = forceTextUpdate || now - lastStatusRefreshMs >= updateIntervalMs
      val allowAutoUpdate = forceTextUpdate || !reactorPanel.isRunning || statusAutoFollowBottom

      if hitRefreshInterval && allowAutoUpdate && (forceTextUpdate || newStatusText != lastStatusText) then
        statusArea.setText(newStatusText)
        lastStatusText = newStatusText
        lastStatusRefreshMs = now

        if statusAutoFollowBottom then
          SwingUtilities.invokeLater(new Runnable:
            override def run(): Unit =
              val bar = statusScroll.getVerticalScrollBar
              suppressStatusAdjustEvent = true
              try bar.setValue(bar.getMaximum)
              finally suppressStatusAdjustEvent = false
          )

  def main(args: Array[String]): Unit =
    SwingUtilities.invokeLater(new Runnable:
      override def run(): Unit =
        val frame = new AntimatterFrame()
        frame.pack()
        frame.setVisible(true)
    )
