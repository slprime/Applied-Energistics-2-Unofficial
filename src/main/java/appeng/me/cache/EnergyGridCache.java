/*
 * This file is part of Applied Energistics 2. Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved. Applied
 * Energistics 2 is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version. Applied Energistics 2 is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details. You should have received a copy of the GNU Lesser General Public License along with
 * Applied Energistics 2. If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.me.cache;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergyGridProvider;
import appeng.api.networking.energy.IEnergyWatcher;
import appeng.api.networking.energy.IEnergyWatcherHost;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPostCacheConstruction;
import appeng.api.networking.events.MENetworkPowerIdleChange;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.events.MENetworkPowerStorage;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.storage.IStackWatcherHost;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.energy.EnergyThreshold;
import appeng.me.energy.EnergyWatcher;

public class EnergyGridCache implements IEnergyGrid {

    private final TreeSet<EnergyThreshold> interests = new TreeSet<>();
    private final double AvgLength = 40.0;
    private final Set<IAEPowerStorage> providers = new LinkedHashSet<>();
    private final Set<IAEPowerStorage> requesters = new LinkedHashSet<>();
    private final Multiset<IEnergyGridProvider> energyGridProviders = HashMultiset.create();
    /*
     * We only keep track of one extractable provider, since player usually only place energy cells in one place
     */
    private WeakReference<IEnergyGridProvider> lastGridProvider = new WeakReference<>(null);
    private final IGrid myGrid;
    private final HashMap<IGridNode, IEnergyWatcher> watchers = new HashMap<>();
    private final Set<IEnergyGrid> localSeen = new HashSet<>();
    /**
     * estimated power available.
     */
    private int availableTicksSinceUpdate = 0;

    private double globalAvailablePower = 0;
    private double globalMaxPower = 0;
    /**
     * idle draw.
     */
    private double drainPerTick = 0;

    private double avgDrainPerTick = 0;
    private double avgInjectionPerTick = 0;
    private double tickDrainPerTick = 0;
    private double tickInjectionPerTick = 0;
    /**
     * power status
     */
    private boolean publicHasPower = false;

    private boolean hasPower = true;
    private boolean infinite = false;
    private boolean updateInfinite = false;
    private long ticksSinceHasPowerChange = 900;
    /**
     * excess power in the system.
     */
    private double extra = 0;

    private IAEPowerStorage lastProvider;
    private IAEPowerStorage lastRequester;
    private PathGridCache pgc;
    private double lastStoredPower = -1;

    public EnergyGridCache(final IGrid g) {
        this.myGrid = g;
    }

    @MENetworkEventSubscribe
    public void postInit(final MENetworkPostCacheConstruction pcc) {
        this.pgc = this.myGrid.getCache(IPathingGrid.class);
    }

    @MENetworkEventSubscribe
    public void EnergyNodeChanges(final MENetworkPowerIdleChange ev) {
        // update power usage based on event.
        final GridNode node = (GridNode) ev.node;
        final IGridBlock gb = node.getGridBlock();

        final double newDraw = gb.getIdlePowerUsage();
        final double diffDraw = newDraw - node.getPreviousDraw();
        node.setPreviousDraw(newDraw);

        this.drainPerTick += diffDraw;
    }

    @MENetworkEventSubscribe
    public void EnergyNodeChanges(final MENetworkPowerStorage ev) {
        if (ev.storage.isAEPublicPowerStorage()) {
            switch (ev.type) {
                case PROVIDE_POWER -> {
                    if (ev.storage.getPowerFlow() != AccessRestriction.WRITE) {
                        this.providers.add(ev.storage);
                    }
                }
                case REQUEST_POWER -> {
                    if (ev.storage.getPowerFlow() != AccessRestriction.READ) {
                        this.requesters.add(ev.storage);
                    }
                }
            }
        } else {
            (new RuntimeException("Attempt to ask the IEnergyGrid to charge a non public energy store."))
                    .printStackTrace();
        }
    }

    @Override
    public void onUpdateTick() {
        if (!this.getInterests().isEmpty()) {
            final double oldPower = this.lastStoredPower;
            this.lastStoredPower = this.getStoredPower();

            final EnergyThreshold low = new EnergyThreshold(Math.min(oldPower, this.lastStoredPower), null);
            final EnergyThreshold high = new EnergyThreshold(Math.max(oldPower, this.lastStoredPower), null);
            for (final EnergyThreshold th : this.getInterests().subSet(low, true, high, true)) {
                ((EnergyWatcher) th.getWatcher()).post(this);
            }
        }

        if (this.updateInfinite) {
            this.updateInfinite();
        }

        this.avgDrainPerTick *= (this.AvgLength - 1) / this.AvgLength;
        this.avgInjectionPerTick *= (this.AvgLength - 1) / this.AvgLength;

        this.avgDrainPerTick += this.tickDrainPerTick / this.AvgLength;
        this.avgInjectionPerTick += this.tickInjectionPerTick / this.AvgLength;

        this.tickDrainPerTick = 0;
        this.tickInjectionPerTick = 0;

        // power information.
        boolean currentlyHasPower = false;

        if (this.drainPerTick > 0.0001) {
            final double drained = this
                    .extractAEPower(this.getIdlePowerUsage(), Actionable.MODULATE, PowerMultiplier.CONFIG);
            currentlyHasPower = drained >= this.drainPerTick - 0.001;
        } else {
            currentlyHasPower = this.extractAEPower(0.1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0;
        }

        // ticks since change.
        if (currentlyHasPower == this.hasPower) {
            this.ticksSinceHasPowerChange++;
        } else {
            this.ticksSinceHasPowerChange = 0;
        }

        // update status..
        this.hasPower = currentlyHasPower;

        // update public status, this buffers power ups for 30 ticks.
        if (this.hasPower && this.ticksSinceHasPowerChange > 30) {
            this.publicPowerState(true, this.myGrid);
        } else if (!this.hasPower) {
            this.publicPowerState(false, this.myGrid);
        }

        this.availableTicksSinceUpdate++;
    }

    @Override
    public double extractAEPower(final double amt, final Actionable mode, final PowerMultiplier pm) {
        this.localSeen.clear();
        return pm.divide(this.extractAEPower(pm.multiply(amt), mode, this.localSeen));
    }

    @Override
    public double getIdlePowerUsage() {
        return this.drainPerTick + this.pgc.getChannelPowerUsage();
    }

    private void publicPowerState(final boolean newState, final IGrid grid) {
        if (this.publicHasPower == newState) {
            return;
        }

        this.publicHasPower = newState;
        ((Grid) this.myGrid).setImportantFlag(0, this.publicHasPower);
        grid.postEvent(new MENetworkPowerStatusChange());
    }

    /**
     * refresh current stored power.
     */
    private void refreshPower() {
        this.availableTicksSinceUpdate = 0;
        this.globalAvailablePower = 0;
        for (final IAEPowerStorage p : this.providers) {
            this.globalAvailablePower += p.getAECurrentPower();
        }
    }

    @Override
    public double extractAEPower(final double amt, final Actionable mode, final Set<IEnergyGrid> seen) {
        if (!seen.add(this)) {
            return 0;
        }

        if (infinite) {
            if (mode == Actionable.MODULATE) {
                this.tickDrainPerTick += amt;
            }
            return amt;
        }

        double extractedPower = this.extra;

        if (mode == Actionable.SIMULATE) {
            extractedPower += this.simulateExtract(extractedPower, amt);

            if (extractedPower < amt) {
                extractedPower = extractFromOtherGrids(amt, mode, seen, extractedPower);
            }

            return extractedPower;
        } else {
            this.extra = 0;
            extractedPower = this.doExtract(extractedPower, amt);
        }

        // got more than we wanted?
        if (extractedPower > amt) {
            this.extra = extractedPower - amt;
            this.globalAvailablePower -= amt;

            this.tickDrainPerTick += amt;
            return amt;
        }

        // call only if we don't have enough power
        if (extractedPower < amt) {
            extractedPower = extractFromOtherGrids(amt, mode, seen, extractedPower);
        }

        // go less or the correct amount?
        this.globalAvailablePower -= extractedPower;
        this.tickDrainPerTick += extractedPower;
        return extractedPower;
    }

    private double extractFromOtherGrids(double amt, Actionable mode, Set<IEnergyGrid> seen, double extractedPower) {
        IEnergyGridProvider energyGridProvider = lastGridProvider.get();
        if (energyGridProvider != null) {
            double extracted = energyGridProvider.extractAEPower(amt - extractedPower, mode, seen);
            if (extracted < 1e-8) {
                lastGridProvider = new WeakReference<>(null);
            } else {
                extractedPower += extracted;
            }
        }

        if (extractedPower < amt) {
            final Iterator<IEnergyGridProvider> i = this.energyGridProviders.iterator();
            while (extractedPower < amt && i.hasNext()) {
                IEnergyGridProvider provider = i.next();
                double extracted = provider.extractAEPower(amt - extractedPower, mode, seen);
                if (extracted > 1e-8) {
                    this.lastGridProvider = new WeakReference<>(provider);
                    extractedPower += extracted;
                }
            }
        }
        return extractedPower;
    }

    @Override
    public double injectAEPower(double amt, final Actionable mode, final Set<IEnergyGrid> seen) {
        if (!seen.add(this)) {
            return 0;
        }

        final double ignore = this.extra;
        amt += this.extra;

        if (mode == Actionable.SIMULATE) {
            final Iterator<IAEPowerStorage> it = this.requesters.iterator();
            while (amt > 0 && it.hasNext()) {
                final IAEPowerStorage node = it.next();
                amt = node.injectAEPower(amt, Actionable.SIMULATE);
            }

            final Iterator<IEnergyGridProvider> i = this.energyGridProviders.iterator();
            while (amt > 0 && i.hasNext()) {
                amt = i.next().injectAEPower(amt, mode, seen);
            }
        } else {
            this.tickInjectionPerTick += amt - ignore;

            while (amt > 0 && !this.requesters.isEmpty()) {
                final IAEPowerStorage node = this.getFirstRequester();

                amt = node.injectAEPower(amt, Actionable.MODULATE);
                if (amt > 0) {
                    this.requesters.remove(node);
                    this.lastRequester = null;
                }
            }

            final Iterator<IEnergyGridProvider> i = this.energyGridProviders.iterator();
            while (amt > 0 && i.hasNext()) {
                final IEnergyGridProvider what = i.next();
                final Set<IEnergyGrid> listCopy = new HashSet<>(seen);

                final double cannotHold = what.injectAEPower(amt, Actionable.SIMULATE, listCopy);
                what.injectAEPower(amt - cannotHold, mode, seen);

                amt = cannotHold;
            }

            this.extra = amt;
        }

        return Math.max(0.0, amt - this.buffer());
    }

    @Override
    public double getEnergyDemand(final double maxRequired, final Set<IEnergyGrid> seen) {
        if (!seen.add(this)) {
            return 0;
        }

        double required = this.buffer() - this.extra;

        final Iterator<IAEPowerStorage> it = this.requesters.iterator();
        while (required < maxRequired && it.hasNext()) {
            final IAEPowerStorage node = it.next();
            if (node.getPowerFlow() != AccessRestriction.READ) {
                required += Math.max(0.0, node.getAEMaxPower() - node.getAECurrentPower());
            }
        }

        final Iterator<IEnergyGridProvider> ix = this.energyGridProviders.iterator();
        while (required < maxRequired && ix.hasNext()) {
            final IEnergyGridProvider node = ix.next();
            required += node.getEnergyDemand(maxRequired - required, seen);
        }

        return required;
    }

    @Override
    public void setHasInfiniteStore(boolean infinite) {
        this.updateInfinite = false;
        this.infinite = infinite;
    }

    @Override
    public boolean getHasInfiniteStore() {
        return this.infinite;
    }

    @Override
    public boolean calculateInfiniteStore(boolean currentInfinite, Set<IEnergyGrid> seen) {
        if (!seen.add(this)) {
            return currentInfinite;
        }

        if (!currentInfinite) {
            currentInfinite = this.providers.stream().anyMatch(IAEPowerStorage::isInfinite);
        }

        for (IEnergyGridProvider gridProvider : this.energyGridProviders) {
            currentInfinite |= gridProvider.calculateInfiniteStore(currentInfinite, seen);
        }

        return currentInfinite;
    }

    private double simulateExtract(double extractedPower, final double amt) {
        final Iterator<IAEPowerStorage> it = this.providers.iterator();

        while (extractedPower < amt && it.hasNext()) {
            final IAEPowerStorage node = it.next();

            final double req = amt - extractedPower;
            final double newPower = node.extractAEPower(req, Actionable.SIMULATE, PowerMultiplier.ONE);
            extractedPower += newPower;
        }

        return extractedPower;
    }

    private double doExtract(double extractedPower, final double amt) {
        while (extractedPower < amt && !this.providers.isEmpty()) {
            final IAEPowerStorage node = this.getFirstProvider();

            final double req = amt - extractedPower;
            final double newPower = node.extractAEPower(req, Actionable.MODULATE, PowerMultiplier.ONE);
            extractedPower += newPower;

            if (newPower < req) {
                this.providers.remove(node);
                this.lastProvider = null;
            }
        }

        return extractedPower;
    }

    private IAEPowerStorage getFirstProvider() {
        if (this.lastProvider == null) {
            final Iterator<IAEPowerStorage> i = this.providers.iterator();
            this.lastProvider = i.hasNext() ? i.next() : null;
        }

        return this.lastProvider;
    }

    @Override
    public double getAvgPowerUsage() {
        return this.avgDrainPerTick;
    }

    @Override
    public double getAvgPowerInjection() {
        return this.avgInjectionPerTick;
    }

    @Override
    public boolean isNetworkPowered() {
        return this.publicHasPower;
    }

    @Override
    public double injectPower(final double amt, final Actionable mode) {
        this.localSeen.clear();
        return this.injectAEPower(amt, mode, this.localSeen);
    }

    private IAEPowerStorage getFirstRequester() {
        if (this.lastRequester == null) {
            final Iterator<IAEPowerStorage> i = this.requesters.iterator();
            this.lastRequester = i.hasNext() ? i.next() : null;
        }

        return this.lastRequester;
    }

    private double buffer() {
        return this.providers.isEmpty() ? 1000.0 : 0.0;
    }

    @Override
    public double getStoredPower() {
        if (this.availableTicksSinceUpdate > 90) {
            this.refreshPower();
        }

        return Math.max(0.0, this.globalAvailablePower);
    }

    @Override
    public double getMaxStoredPower() {
        return this.globalMaxPower;
    }

    @Override
    public double getEnergyDemand(final double maxRequired) {
        this.localSeen.clear();
        return this.getEnergyDemand(maxRequired, this.localSeen);
    }

    private void updateInfinite() {
        Set<IEnergyGrid> grids = new HashSet<>();
        boolean infinite = this.calculateInfiniteStore(false, grids);
        for (IEnergyGrid grid : grids) {
            grid.setHasInfiniteStore(infinite);
        }
    }

    @Override
    public void removeNode(final IGridNode node, final IGridHost machine) {
        if (machine instanceof IEnergyGridProvider) {
            this.energyGridProviders.remove(machine);
            if (lastGridProvider.get() == machine) {
                lastGridProvider = new WeakReference<>(null);
            }
            // removing a quartz fiber will not cause a net to go from finite to infinite
            this.updateInfinite = true;
        }

        // idle draw.
        final GridNode gridNode = (GridNode) node;
        this.drainPerTick -= gridNode.getPreviousDraw();

        // power storage.
        if (machine instanceof IAEPowerStorage ps) {
            if (ps.isAEPublicPowerStorage()) {
                if (ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalMaxPower -= ps.getAEMaxPower();
                    this.globalAvailablePower -= ps.getAECurrentPower();
                }

                if (this.lastProvider == machine) {
                    this.lastProvider = null;
                }

                if (this.lastRequester == machine) {
                    this.lastRequester = null;
                }

                this.providers.remove(machine);
                this.requesters.remove(machine);

                if (((IAEPowerStorage) machine).isInfinite()) {
                    this.updateInfinite = true;
                }
            }
        }

        if (machine instanceof IStackWatcherHost) {
            final IEnergyWatcher myWatcher = this.watchers.get(machine);
            if (myWatcher != null) {
                myWatcher.clear();
                this.watchers.remove(machine);
            }
        }
    }

    @Override
    public void addNode(final IGridNode node, final IGridHost machine) {
        if (machine instanceof IEnergyGridProvider) {
            this.energyGridProviders.add((IEnergyGridProvider) machine);
            // adding a quartz fiber will not cause a net to go from with infinite to finite
            this.updateInfinite |= !infinite;
        }

        // idle draw...
        final GridNode gridNode = (GridNode) node;
        final IGridBlock gb = gridNode.getGridBlock();
        gridNode.setPreviousDraw(gb.getIdlePowerUsage());
        this.drainPerTick += gridNode.getPreviousDraw();

        // power storage
        if (machine instanceof IAEPowerStorage ps) {
            if (ps.isAEPublicPowerStorage()) {
                final double max = ps.getAEMaxPower();
                final double current = ps.getAECurrentPower();

                if (ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalMaxPower += ps.getAEMaxPower();
                }

                if (current > 0 && ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalAvailablePower += current;
                    this.providers.add(ps);
                }

                if (current < max && ps.getPowerFlow() != AccessRestriction.READ) {
                    this.requesters.add(ps);
                }

                if (((IAEPowerStorage) machine).isInfinite()) {
                    this.updateInfinite = true;
                }
            }
        }

        if (machine instanceof IEnergyWatcherHost swh) {
            final EnergyWatcher iw = new EnergyWatcher(this, swh);
            this.watchers.put(node, iw);
            swh.updateWatcher(iw);
        }

        this.myGrid.postEventTo(node, new MENetworkPowerStatusChange());
    }

    @Override
    public void onSplit(final IGridStorage storageB) {
        // it's not clear as what this method do, set update to true just in case
        this.updateInfinite = true;
        this.extra /= 2;
        storageB.dataObject().setDouble("extraEnergy", this.extra);
    }

    @Override
    public void onJoin(final IGridStorage storageB) {
        // it's not clear as what this method do, set update to true just in case
        this.updateInfinite = true;
        this.extra += storageB.dataObject().getDouble("extraEnergy");
    }

    @Override
    public void populateGridStorage(final IGridStorage storage) {
        storage.dataObject().setDouble("extraEnergy", this.extra);
    }

    public TreeSet<EnergyThreshold> getInterests() {
        return this.interests;
    }
}
