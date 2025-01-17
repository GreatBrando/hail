#include "hail/RegionPool.h"
#include "hail/NativePtr.h"
#include "hail/Upcalls.h"
#include <memory>
#include <vector>
#include <utility>
#include <algorithm>
#include <iostream>

namespace hail {

void RegionPtr::clear() {
  if (region_ != nullptr) {
    --(region_->references_);
    if (region_->references_ == 0) {
      region_->clear();
      region_->pool_->free_regions_.push_back(region_);
    }
    region_ = nullptr;
  }
}

Region::Region(RegionPool * pool) :
pool_(pool),
block_offset_(0),
current_block_(pool->get_block()) { }

char * Region::allocate_new_block(size_t n) {
  used_blocks_.push_back(std::move(current_block_));
  current_block_ = pool_->get_block();
  block_offset_ = n;
  return current_block_.get();
}

char * Region::allocate_big_chunk(size_t n) {
  big_chunks_.push_back(std::make_unique<char[]>(n));
  return big_chunks_.back().get();
}

void Region::clear() {
  block_offset_ = 0;
  std::move(std::begin(used_blocks_), std::end(used_blocks_), std::back_inserter(pool_->free_blocks_));
  used_blocks_.clear();
  big_chunks_.clear();
  parents_.clear();
}

RegionPtr Region::get_region() {
  return pool_->get_region();
}

void Region::add_reference_to(RegionPtr region) {
  parents_.push_back(std::move(region));
}

size_t Region::get_num_parents() {
  return parents_.size();
}

void Region::set_num_parents(int n) {
  parents_.resize(n, nullptr);
}

void Region::set_parent_reference(RegionPtr region, int i) {
  parents_[i] = region;
}

RegionPtr Region::get_parent_reference(int i) { return parents_[i]; }

RegionPtr Region::new_parent_reference(int i) {
  auto r = get_region();
  parents_[i] = r;
  return r;
}

void Region::clear_parent_reference(int i) {
  parents_[i] = nullptr;
}

std::unique_ptr<char[]> RegionPool::get_block() {
  if (free_blocks_.empty()) {
    return std::make_unique<char[]>(REGION_BLOCK_SIZE);
  }
  std::unique_ptr<char[]> block = std::move(free_blocks_.back());
  free_blocks_.pop_back();
  return block;
}

RegionPtr RegionPool::new_region() {
  regions_.emplace_back(new Region(this));
  return RegionPtr(regions_.back().get());
}

RegionPtr RegionPool::get_region() {
  if (free_regions_.empty()) {
    return new_region();
  }
  Region * region = std::move(free_regions_.back());
  free_regions_.pop_back();
  return RegionPtr(region);
}

void ScalaRegionPool::own(RegionPool &&pool) {
  for (auto &region : pool.regions_) {
    if (region->references_ != 0) {
      region->pool_ = &this->pool_;
      this->pool_.regions_.push_back(std::move(region));
    }
  }
}

ScalaRegion::ScalaRegion(ScalaRegionPool * pool) :
region_(pool->pool_.get_region()) { }

ScalaRegion::ScalaRegion(std::nullptr_t) :
region_(nullptr) { }

#define REGIONMETHOD(rtype, scala_class, scala_method) \
  extern "C" __attribute__((visibility("default"))) \
    rtype Java_is_hail_annotations_##scala_class##_##scala_method

REGIONMETHOD(void, RegionPool, nativeCtor)(
  JNIEnv* env,
  jobject thisJ
) {
  NativeObjPtr ptr = std::make_shared<ScalaRegionPool>();
  init_NativePtr(env, thisJ, &ptr);
}

REGIONMETHOD(jint, RegionPool, numRegions)(
  JNIEnv* env,
  jobject thisJ
) {
  auto pool = static_cast<ScalaRegionPool*>(get_from_NativePtr(env, thisJ));
  return (jint) pool->pool_.num_regions();
}

REGIONMETHOD(jint, RegionPool, numFreeRegions)(
  JNIEnv* env,
  jobject thisJ
) {
  auto pool = static_cast<ScalaRegionPool*>(get_from_NativePtr(env, thisJ));
  return (jint) pool->pool_.num_free_regions();
}

REGIONMETHOD(jint, RegionPool, numFreeBlocks)(
  JNIEnv* env,
  jobject thisJ
) {
    auto pool = static_cast<ScalaRegionPool*>(get_from_NativePtr(env, thisJ));
    return (jint) pool->pool_.num_free_blocks();
}

REGIONMETHOD(void, Region, nativeCtor)(
  JNIEnv* env,
  jobject thisJ,
  jobject poolJ
) {
  auto pool = static_cast<ScalaRegionPool*>(get_from_NativePtr(env, poolJ));
  NativeObjPtr ptr = std::make_shared<ScalaRegion>(pool);
  init_NativePtr(env, thisJ, &ptr);
}

REGIONMETHOD(void, Region, initEmpty)(
  JNIEnv* env,
  jobject thisJ
) {
  NativeObjPtr ptr = std::make_shared<ScalaRegion>(nullptr);
  init_NativePtr(env, thisJ, &ptr);
}

REGIONMETHOD(void, Region, clearButKeepMem)(
  JNIEnv* env,
  jobject thisJ
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  r->region_->clear();
}

REGIONMETHOD(void, Region, nativeAlign)(
  JNIEnv* env,
  jobject thisJ,
  jlong a
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  r->region_->align(a);
}

REGIONMETHOD(jlong, Region, nativeAlignAllocate)(
  JNIEnv* env,
  jobject thisJ,
  jlong a,
  jlong n
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  return reinterpret_cast<jlong>(r->region_->allocate((size_t)a, (size_t)n));
}

REGIONMETHOD(jlong, Region, nativeAllocate)(
  JNIEnv* env,
  jobject thisJ,
  jlong n
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  return reinterpret_cast<jlong>(r->region_->allocate((size_t)n));
}

REGIONMETHOD(void, Region, nativeReference)(
  JNIEnv* env,
  jobject thisJ,
  jobject otherJ
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  auto r2 = static_cast<ScalaRegion*>(get_from_NativePtr(env, otherJ));
  r->region_->add_reference_to(r2->region_);
}

REGIONMETHOD(void, Region, nativeRefreshRegion)(
  JNIEnv* env,
  jobject thisJ
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  r->region_ = r->region_->get_region();
}

REGIONMETHOD(void, Region, nativeClearRegion)(
  JNIEnv* env,
  jobject thisJ
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  r->region_ = nullptr;
}

REGIONMETHOD(jint, Region, nativeGetNumParents)(
  JNIEnv* env,
  jobject thisJ
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  return (jint) r->region_->get_num_parents();
}

REGIONMETHOD(void, Region, nativeSetNumParents)(
  JNIEnv* env,
  jobject thisJ,
  jint i
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  r->region_->set_num_parents((int) i);
}

REGIONMETHOD(void, Region, nativeSetParentReference)(
  JNIEnv* env,
  jobject thisJ,
  jobject otherJ,
  jint i
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  auto r2 = static_cast<ScalaRegion*>(get_from_NativePtr(env, otherJ));
  r->region_->set_parent_reference(r2->region_, (int) i);
}

REGIONMETHOD(void, Region, nativeGetParentReferenceInto)(
  JNIEnv* env,
  jobject thisJ,
  jobject otherJ,
  jint i
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  auto r2 = static_cast<ScalaRegion*>(get_from_NativePtr(env, otherJ));
  r2->region_ = r->region_->get_parent_reference((int) i);
  if (r2->region_.get() == nullptr) {
    r2->region_ = r->region_->new_parent_reference((int) i);
  }
}

REGIONMETHOD(void, Region, nativeClearParentReference)(
  JNIEnv* env,
  jobject thisJ,
  jint i
) {
  auto r = static_cast<ScalaRegion*>(get_from_NativePtr(env, thisJ));
  r->region_->clear_parent_reference((int) i);
}

}