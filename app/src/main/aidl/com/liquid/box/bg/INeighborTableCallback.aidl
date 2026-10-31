package com.liquid.box.bg;

import com.liquid.box.bg.ParceledListSlice;

interface INeighborTableCallback {
    oneway void onNeighborTableUpdated(in ParceledListSlice entries);
}
