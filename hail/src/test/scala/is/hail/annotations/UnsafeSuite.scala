package is.hail.annotations

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import is.hail.HailSuite
import is.hail.check._
import is.hail.expr.types.physical._
import is.hail.expr.types.virtual.{TArray, TStruct, Type}
import is.hail.io._
import is.hail.utils._
import org.apache.spark.sql.Row
import org.testng.annotations.Test

import scala.util.Random

class UnsafeSuite extends HailSuite {
  def subsetType(t: Type): Type = {
    t match {
      case t: TStruct =>
        TStruct(t.required,
          t.fields.filter(_ => Random.nextDouble() < 0.4)
            .map(f => f.name -> f.typ): _*)

      case t: TArray =>
        TArray(subsetType(t.elementType), t.required)

      case _ => t
    }
  }

  def subset(t: Type, requestedType: Type, a: Annotation): Annotation = {
    t match {
      case t2: TStruct =>
        val requestedType2 = requestedType.asInstanceOf[TStruct]
        if (a == null)
          null
        else {
          val a2 = a.asInstanceOf[Row]
          Row.fromSeq(requestedType2.fields.map { rf =>
            val f = t2.field(rf.name)
            subset(f.typ, rf.typ, a2.get(f.index))
          })
        }

      case _ => a
    }
  }

  @Test def testCodec() {
    val region = Region()
    val region2 = Region()
    val region3 = Region()
    val region4 = Region()
    val rvb = new RegionValueBuilder(region)

    val path = tmpDir.createTempFile(extension = "ser")

    val g = Type.genStruct
      .flatMap(t => Gen.zip(Gen.const(t), t.genValue))
      .filter { case (t, a) => a != null }
    val p = Prop.forAll(g) { case (t, a) =>
      assert(t.typeCheck(a))

      val requestedType = subsetType(t).asInstanceOf[TStruct]
      val a2 = subset(t, requestedType, a)
      assert(requestedType.typeCheck(a2))

      CodecSpec.codecSpecs.foreach { codecSpec =>
        region.clear()
        rvb.start(t.physicalType)
        rvb.addRow(t, a.asInstanceOf[Row])
        val offset = rvb.end()
        val ur = new UnsafeRow(t.physicalType, region, offset)

        val aos = new ByteArrayOutputStream()
        val en = codecSpec.buildEncoder(t.physicalType)(aos)
        en.writeRegionValue(region, offset)
        en.flush()

        region2.clear()
        val ais = new ByteArrayInputStream(aos.toByteArray)
        val dec = codecSpec.buildDecoder(t.physicalType, t.physicalType)(ais)
        val offset2 = dec.readRegionValue(region2)
        val ur2 = new UnsafeRow(t.physicalType, region2, offset2)
        assert(t.typeCheck(ur2))
        assert(t.valuesSimilar(a, ur2))

        region3.clear()
        val ais3 = new ByteArrayInputStream(aos.toByteArray)
        val dec3 = codecSpec.buildDecoder(t.physicalType, requestedType.physicalType)(ais3)
        val offset3 = dec3.readRegionValue(region3)
        val ur3 = new UnsafeRow(requestedType.physicalType, region3, offset3)
        assert(requestedType.typeCheck(ur3))
        assert(requestedType.valuesSimilar(a2, ur3))

        val aos2 = new ByteArrayOutputStream()
        val en2 = codecSpec.buildEncoder(t.physicalType, requestedType.physicalType)(aos2)
        en2.writeRegionValue(region, offset)
        en2.flush()

        region4.clear()
        val ais4 = new ByteArrayInputStream(aos2.toByteArray)
        val dec4 = codecSpec.buildDecoder(requestedType.physicalType, requestedType.physicalType)(ais4)
        val offset4 = dec4.readRegionValue(region4)
        val ur4 = new UnsafeRow(requestedType.physicalType, region4, offset4)
        assert(requestedType.typeCheck(ur4))
        if (!requestedType.valuesSimilar(a2, ur4)) {
          println(t)
          println(requestedType)
          println(a2)
          println(ur4)
        }
        assert(requestedType.valuesSimilar(a2, ur4))
      }

      true
    }
    p.check()
  }

  @Test def testBufferWriteReadDoubles() {
    val a = Array(1.0, -349.273, 0.0, 9925.467, 0.001)

    CodecSpec.bufferSpecs.foreach { bufferSpec =>
      val out = new ByteArrayOutputStream()
      val outputBuffer = bufferSpec.buildOutputBuffer(out)
      outputBuffer.writeDoubles(a)
      outputBuffer.flush()

      val in = new ByteArrayInputStream(out.toByteArray)
      val inputBuffer = bufferSpec.buildInputBuffer(in)
      val a2 = new Array[Double](5)
      inputBuffer.readDoubles(a2)

      assert(a sameElements a2)
    }
  }

  @Test def testRegionValue() {
    val region = Region()
    val region2 = Region()
    val rvb = new RegionValueBuilder(region)
    val rvb2 = new RegionValueBuilder(region2)

    val g = Type.genArb
      .flatMap(t => Gen.zip(Gen.const(t), t.genValue, Gen.choose(0, 100), Gen.choose(0, 100)))
      .filter { case (t, a, n, n2) => a != null }
    val p = Prop.forAll(g) { case (t, a, n, n2) =>
      val pt = t.physicalType
      t.typeCheck(a)

      // test addAnnotation
      region.clear()
      region.allocate(1, n) // preallocate

      rvb.start(pt)
      rvb.addAnnotation(t, a)
      val offset = rvb.end()

      val ur = UnsafeRow.read(pt, region, offset)
      assert(t.valuesSimilar(a, ur), s"$a vs $ur")

      // test visitor
      val rv = RegionValue(region, offset)
      rv.pretty(pt)

      // test addAnnotation from ur
      region2.clear()
      region2.allocate(1, n2) // preallocate
      rvb2.start(pt)
      rvb2.addAnnotation(t, ur)
      val offset2 = rvb2.end()

      val ur2 = UnsafeRow.read(pt, region2, offset2)
      assert(t.valuesSimilar(a, ur2), s"$a vs $ur2")

      // test addRegionValue
      region2.clear()
      region2.allocate(1, n2) // preallocate
      rvb2.start(pt)
      rvb2.addRegionValue(pt, region, offset)
      val offset3 = rvb2.end()
      val ur3 = UnsafeRow.read(pt, region2, offset3)
      assert(t.valuesSimilar(a, ur3), s"$a vs $ur3")

      // test addRegionValue nested
      t match {
        case t: TStruct =>
          region2.clear()
          region2.allocate(1, n) // preallocate
          rvb2.start(t.physicalType)
          rvb2.addAnnotation(t, Row.fromSeq(a.asInstanceOf[Row].toSeq))
          val offset4 = rvb2.end()
          val ur4 = new UnsafeRow(t.physicalType, region2, offset4)
          assert(t.valuesSimilar(a, ur4))
        case _ =>
      }

      // test addRegionValue to same region
      rvb.start(pt)
      rvb.addRegionValue(pt, region, offset)
      val offset5 = rvb.end()
      val ur5 = UnsafeRow.read(pt, region, offset5)
      assert(t.valuesSimilar(a, ur5))

      // test addRegionValue to same region nested
      t match {
        case t: TStruct =>
          rvb.start(t.physicalType)
          rvb.addAnnotation(t, Row.fromSeq(a.asInstanceOf[Row].toSeq))
          val offset6 = rvb.end()
          val ur6 = new UnsafeRow(t.physicalType, region, offset6)
          assert(t.valuesSimilar(a, ur6))
        case _ =>
      }

      true
    }
    p.check()
  }

  @Test def testRegion() {
    val buff = Region()

    val addrA = buff.appendLong(124L)
    val addrB = buff.appendByte(2)
    val addrC = buff.appendByte(1)
    val addrD = buff.appendByte(4)
    val addrE = buff.appendInt(1234567)
    val addrF = buff.appendDouble(1.1)

    assert(buff.loadLong(addrA) == 124L)
    assert(buff.loadByte(addrB) == 2)
    assert(buff.loadByte(addrC) == 1)
    assert(buff.loadByte(addrD) == 4)
    assert(buff.loadInt(addrE) == 1234567)
    assert(buff.loadDouble(addrF) == 1.1)
  }

  val g = (for {
    s <- Gen.size
    // prefer smaller type and bigger values
    fraction <- Gen.choose(0.1, 0.3)
    x = (fraction * s).toInt
    y = s - x
    t <- Type.genStruct.resize(x)
    v <- t.genNonmissingValue.resize(y)
  } yield (t, v)).filter(_._2 != null)

  @Test def testPacking() {

    def makeStruct(types: PType*): PStruct = {
      PStruct(types.zipWithIndex.map { case (t, i) => (s"f$i", t) }: _*)
    }

    val t1 = makeStruct( // missing byte is 0
      PInt32(), //4-8
      PInt32(), //8-12
      PFloat64(), //16-24
      PBoolean(), //1-2
      PBoolean(), //2-3
      PBoolean(), //3-4
      PBoolean(), //12-13
      PBoolean()) //13-14
    assert(t1.byteOffsets.toSeq == Seq(4, 8, 16, 1, 2, 3, 12, 13))
    assert(t1.byteSize == 24)

    val t2 = makeStruct( //missing bytes 0, 1
      PBoolean(), //2-3
      PInt32(), //4-8
      PInt32(), //8-12
      PFloat64(), //16-24
      PInt32(), //12-16
      PInt32(), //24-28
      PFloat64(), //32-40
      PInt32(), //28-32
      PBoolean(), //3-4
      PFloat64(), //40-48
      PBoolean()) //48-49

    assert(t2.byteOffsets.toSeq == Seq(2, 4, 8, 16, 12, 24, 32, 28, 3, 40, 48))
    assert(t2.byteSize == 49)

    val t3 = makeStruct((0 until 512).map(_ => PFloat64()): _*)
    assert(t3.byteSize == (512 / 8) + 512 * 8)
    val t4 = makeStruct((0 until 256).flatMap(_ => Iterator(PInt32(), PInt32(), PFloat64(), PBoolean())): _*)
    assert(t4.byteSize == 256 * 4 / 8 + 256 * 4 * 2 + 256 * 8 + 256)
  }

  @Test def testEmptySize() {
    assert(PStruct().byteSize == 0)
  }

  @Test def testUnsafeOrdering() {
    val region = Region()
    val region2 = Region()
    val rvb = new RegionValueBuilder(region)
    val rvb2 = new RegionValueBuilder(region2)

    val g = PType.genStruct
      .flatMap(t => Gen.zip(Gen.const(t), Gen.zip(t.virtualType.genValue, t.virtualType.genValue)))
      .filter { case (t, (a1, a2)) => a1 != null && a2 != null }
      .resize(10)
    val p = Prop.forAll(g) { case (t, (a1, a2)) =>

      val tv = t.virtualType

      tv.typeCheck(a1)
      tv.typeCheck(a2)

      region.clear()
      rvb.start(tv.physicalType.fundamentalType)
      rvb.addRow(tv, a1.asInstanceOf[Row])
      val offset = rvb.end()

      val ur1 = new UnsafeRow(tv.physicalType, region, offset)
      assert(tv.valuesSimilar(a1, ur1))

      region2.clear()
      rvb2.start(tv.physicalType.fundamentalType)
      rvb2.addRow(tv, a2.asInstanceOf[Row])
      val offset2 = rvb2.end()

      val ur2 = new UnsafeRow(tv.physicalType, region2, offset2)
      assert(tv.valuesSimilar(a2, ur2))

      val ord = tv.ordering
      val uord = t.unsafeOrdering()

      val c1 = ord.compare(a1, a2)
      val c2 = ord.compare(ur1, ur2)
      val c3 = uord.compare(ur1.region, ur1.offset, ur2.region, ur2.offset)

      val p1 = math.signum(c1) == math.signum(c2)
      val p2 = math.signum(c2) == math.signum(c3)

      val p = p1 && p2
      if (!p) {
        println(s"t=$t")
        println(s"a1=$a1")
        println(s"a2=$a2")
        println(s"c1=$c1, c2=$c2, c3=$c3")
      }
      p
    }
    p.check()
  }

  @Test def testRegionAllocation() {
    val pool = RegionPool.get

    case class Counts(regions: Int, freeRegions: Int, freeBlocks: Int) {
      def allocateRegion(): Counts =
        if (freeRegions == 0)
          copy(regions = regions + 1)
        else copy(freeRegions = freeRegions - 1)

      def refreshRegion(nDependentRegions: Int = 0, nBlocks: Int = 0): Counts =
        if (freeRegions == 0)
          Counts(regions + 1, nDependentRegions + 1, freeBlocks - nBlocks)
        else Counts(regions, freeRegions + nDependentRegions, freeBlocks - nBlocks)
    }
    def getCurrentCounts: Counts = Counts(pool.numRegions(), pool.numFreeRegions(), pool.numFreeBlocks())

    var counts = getCurrentCounts

    val region = Region()
    assert(getCurrentCounts == counts.allocateRegion())
    counts = getCurrentCounts

    val region2 = Region()
    assert(getCurrentCounts == counts.allocateRegion())
    counts = getCurrentCounts

    region.reference(region2)
    region2.refreshRegion()
    assert(getCurrentCounts == counts.allocateRegion())
    counts = getCurrentCounts

    region.refreshRegion()
    assert(getCurrentCounts == counts.refreshRegion(1))

    val region3 = Region()
    region3.reference(region)
    region3.reference(region2)

    counts = getCurrentCounts
    region.refreshRegion()
    region2.refreshRegion()
    assert(getCurrentCounts == counts.allocateRegion().allocateRegion())

    counts = getCurrentCounts
    region3.refreshRegion()
    assert(getCurrentCounts == counts.refreshRegion(2))
  }

  @Test def testRegionReferences() {
    def offset(region: Region) = region.allocate(0)
    def numUsed(): Int = RegionPool.get.numRegions() - RegionPool.get.numFreeRegions()
    def assertUsesRegions[T](n: Int)(f: => T): T = {
      val usedRegionCount = numUsed()
      val res = f
      assert(usedRegionCount == numUsed() - n)
      res
    }

    val region = Region()
    region.setNumParents(5)

    val off4 = using(assertUsesRegions(1) { region.getParentReference(4) }) { r =>
      offset(r)
    }

    val off2 = Region.scoped { r =>
      region.setParentReference(r, 2)
      offset(r)
    }

    using(region.getParentReference(2)) { r =>
      assert(offset(r) == off2)
    }

    using(region.getParentReference(4)) { r =>
      assert(offset(r) == off4)
    }

    assertUsesRegions(-1) { region.clearParentReference(2) }
    assertUsesRegions(-1) { region.clearParentReference(4) }
  }

  // Tests for Region serialization have been removed since an off-heap Region
  // contains absolute addresses and can't be serialized/deserialized without 
  // knowing the RegionValue Type.
}
