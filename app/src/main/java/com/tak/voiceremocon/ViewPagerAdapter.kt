package com.tak.voiceremocon

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

  override fun getItemCount(): Int = 4

  override fun createFragment(position: Int): Fragment {
    return when (position) {
      0 -> RemoconFragment()
      1 -> GenericControlsFragment()
      2 -> RecognitionFragment()
      3 -> LearningFragment()
      else -> throw IllegalArgumentException("Invalid position: $position")
    }
  }
}
