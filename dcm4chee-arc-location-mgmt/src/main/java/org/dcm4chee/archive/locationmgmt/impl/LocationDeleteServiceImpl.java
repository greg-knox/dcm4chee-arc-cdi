/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2013
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.archive.locationmgmt.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.jms.JMSException;

import org.dcm4che3.net.Device;
import org.dcm4chee.archive.ArchiveServiceReloaded;
import org.dcm4chee.archive.ArchiveServiceStarted;
import org.dcm4chee.archive.ArchiveServiceStopped;
import org.dcm4chee.archive.conf.ArchiveDeviceExtension;
import org.dcm4chee.archive.entity.ExternalRetrieveLocation;
import org.dcm4chee.archive.entity.Instance;
import org.dcm4chee.archive.entity.Location;
import org.dcm4chee.archive.event.StartStopReloadEvent;
import org.dcm4chee.archive.locationmgmt.DeleterService;
import org.dcm4chee.archive.locationmgmt.LocationDeleteResult;
import org.dcm4chee.archive.locationmgmt.LocationDeleteResult.DeletionStatus;
import org.dcm4chee.archive.locationmgmt.LocationMgmt;
import org.dcm4chee.storage.conf.Availability;
import org.dcm4chee.storage.conf.StorageDeviceExtension;
import org.dcm4chee.storage.conf.StorageSystem;
import org.dcm4chee.storage.conf.StorageSystemGroup;
import org.dcm4chee.storage.conf.StorageSystemStatus;
import org.dcm4chee.storage.spi.StorageSystemProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 * 
 */

@ApplicationScoped
public class LocationDeleteServiceImpl implements DeleterService {

    private static final Logger LOG = LoggerFactory
            .getLogger(LocationDeleteServiceImpl.class);

    @Inject
    private Device device;

    @Inject
    private LocationMgmt locationManager;

    @Inject
    private javax.enterprise.inject.Instance<StorageSystemProvider> storageSystemProviders;

    private int lastPollInterval;

    private int deletionRetries;

    private ScheduledFuture<?> deleteTask;

    private Map<String, Date> lastDVDCalculationDateMap;

    private Map<String, Long> lastCalculatedDVDInBytesMap;

    @PostConstruct
    public void init() {
        lastDVDCalculationDateMap = new HashMap<String, Date>();
        lastCalculatedDVDInBytesMap = new HashMap<String, Long>();
    }

    @Override
    public void freeUpSpaceDeleteSeries(String seriesInstanceUID, StorageSystemGroup group) {
        freeSpaceOnRequest(null, seriesInstanceUID, group);
    }

    @Override
    public void freeUpSpaceDeleteStudy(String studyInstanceUID, StorageSystemGroup group) {
        freeSpaceOnRequest(studyInstanceUID, null, group);
    }

    @Override
    public void freeUpSpace(StorageSystemGroup groupToFree) {
        freeSpace(groupToFree);
    }

    @Override
    public void freeUpSpace() {

        StorageDeviceExtension stgDevExt = device.getDeviceExtension(StorageDeviceExtension.class);
        Map<String,StorageSystemGroup>groups = stgDevExt.getStorageSystemGroups();
        
        if(groups == null) {
            LOG.error("Location Deleter Service: No storage Groups configured, "
                    + " Malformed Configuration, some archive services might not function");
            return;
        }
        
        for(String groupID : groups.keySet()) {
            //check need to free up space
            StorageSystemGroup group = groups.get(groupID);
            try {
                if(canDeleteNow(group) || emergencyReached(group))
                freeSpace(group);
            } catch (IOException e) {
                LOG.error("Unable to calculate emergency case, "
                        + "error calculating emergency for group "
                        + "{} - reason {}", groupID, e);
            }
            catch (Throwable t) {
                LOG.error("Exception occured while attempting to "
                        + "freespace from group {} - reason {}", groupID, t);
            }
        }
    }

    @Override
    public boolean validateGroupForDeletion(StorageSystemGroup group) {

        if (checkArchivingConstraints(group)
            && checkDeletionConstraints(group) 
                    && checkStudyRetentionContraints(group))
                    return true;
        return false;
    }

    public void onArchiveServiceStarted(
            @Observes @ArchiveServiceStarted StartStopReloadEvent start) {

        int pollInterval = device.getDeviceExtension(
                ArchiveDeviceExtension.class).getDeletionServicePollInterval();
        if (pollInterval > 0)
            startPolling(pollInterval);
    }

    public void onArchiveServiceStopped(
            @Observes @ArchiveServiceStopped StartStopReloadEvent stop) {

        stopPolling();
    }

    public void onArchiveSeriviceReloaded(
            @Observes @ArchiveServiceReloaded StartStopReloadEvent reload) {

        init();
        int pollInterval = device.getDeviceExtension(
                ArchiveDeviceExtension.class).getDeletionServicePollInterval();
        if (lastPollInterval != pollInterval) {
            if (deleteTask != null) {
                stopPolling();
                startPolling(pollInterval);
            } else
                startPolling(pollInterval);
        }
    }

    @Override
    public long calculateDataVolumePerDayInBytes(String groupID) {

        StorageDeviceExtension stgDevExt = device
                .getDeviceExtension(StorageDeviceExtension.class);
        StorageSystemGroup group = stgDevExt.getStorageSystemGroup(groupID);
        if (group == null) {
            LOG.error("Location Deleter Service: Group {} not configured, "
                            + " Malformed Configuration, some archive services"
                            + " might not function", groupID);
            return lastCalculatedDVDInBytesMap.get(groupID) != null 
                    ? lastCalculatedDVDInBytesMap.get(groupID) : 0L;
        }
        if (!isDueCalculation(group.getDataVolumePerDayCalculationRange(),
                lastDVDCalculationDateMap.get(groupID))) {

            return lastCalculatedDVDInBytesMap.get(groupID);
        } else {
            long dvdInBytes = locationManager.calculateDataVolumePerDayInBytes(
                    groupID, group.getDataVolumePerDayAverageOnNDays());
            lastCalculatedDVDInBytesMap.put(groupID, dvdInBytes);
            lastDVDCalculationDateMap.put(groupID, new Date());
            return dvdInBytes;
        }
    }

    private LocationDeleteResult freeSpaceOnRequest(String studyInstanceUID, String seriesInstanceUID,
            StorageSystemGroup group) {
        if (validateGroupForDeletion(group)) {
            try{
            int minTimeToKeepStudy = group.getMinTimeStudyNotAccessed();
            String minTimeToKeppStudyUnit = group
                    .getMinTimeStudyNotAccessedUnit();
            List<Instance> allInstancesDueDeleteOnGroup = (ArrayList<Instance>) 
                    locationManager.findInstancesDueDelete(minTimeToKeepStudy
                            , minTimeToKeppStudyUnit, group.getGroupID(),studyInstanceUID, seriesInstanceUID);
            List<Instance> actualInstancesToDelete = (ArrayList<Instance>) filterCopiesExist(
                    (ArrayList<Instance>) allInstancesDueDeleteOnGroup, group);
            
            markCorrespondingStudyAndScheduleForDeletion(studyInstanceUID,
                    group, actualInstancesToDelete);
            
            handleFailedToDeleteLocations(group);
            }
            catch (Exception e) {
                return new LocationDeleteResult(DeletionStatus.FAILED,
                        "Deletion Failed, Reason " + getFailureReason(e));
            }
            return new LocationDeleteResult(DeletionStatus.SCHEDULED, "No Failure");
        }
        return new LocationDeleteResult(DeletionStatus.CRITERIA_NOT_MET,
                "Validation Criteria not met, rule on group " + group.getGroupID()
                        + "can not be applied");
    }

    private String getFailureReason(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, true);
        e.printStackTrace(pw);
        LOG.info("Error scheduling delete requests, reason - {}", e);
        return sw.getBuffer().toString();
    }

    private boolean emergencyReached(StorageSystemGroup group) throws IOException {

        List<StorageSystem> tmpFlaggedsystems = new ArrayList<StorageSystem>();
        for(String systemID : group.getStorageSystems().keySet()) {
            StorageSystem system = group.getStorageSystem(systemID);
            StorageSystemProvider provider = system
                    .getStorageSystemProvider(storageSystemProviders);
            if(isUsableSystem(system)) {
                if (system.getMinFreeSpace() != null
                        && system.getMinFreeSpaceInBytes() == -1L)
                    system.setMinFreeSpaceInBytes(provider.getTotalSpace()
                            * Integer.parseInt(system.getMinFreeSpace()
                                    .replace("%", ""))/100);
                long usableSpace = provider.getUsableSpace() ;
                long minSpaceRequired = system.getMinFreeSpaceInBytes();
                if(usableSpace < minSpaceRequired) {
                    system.setStorageSystemStatus(StorageSystemStatus.FULL);
                    system.getStorageDeviceExtension().setDirty(true);
                LOG.info("System {} is about to fill up, "
                        + "emergency deletion in order, currently flagged dirty", system);
                tmpFlaggedsystems.add(system);
                }
            }
        }
        return tmpFlaggedsystems.isEmpty() ?  false : true;
    }

    private boolean canDeleteNow(StorageSystemGroup group) {

        String deleteServiceAllowedInterval = group
                .getDeleteServiceAllowedInterval();
        if(deleteServiceAllowedInterval == null)
            return false;
        if(deleteServiceAllowedInterval.split("-").length < 1) {
            LOG.error("Location Deleter Service: Allowed interval for deletion"
                    + " is not configured properly, service will not try to"
                    + " free up disk space on group {}",group.getGroupID());
            return false;
        }
        try{
        int min = Integer.parseInt(deleteServiceAllowedInterval.split("-")[0]);
        int max = Integer.parseInt(deleteServiceAllowedInterval.split("-")[1]);
        int hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        
        return hourNow > min && hourNow < max ? true : false;
        } catch (Exception e) {
            LOG.error("Location Deleter Service: Unable to decide allowed"
                    + " deletion interval , service will not attempt to"
                    + " free up disk space on group {} - reason {}", group.getGroupID(), e);
            return false;
        }
    }

    private boolean isDueCalculation(String dataVolumePerDayCalculationRange,
            Date lastDVDCalculationDate) {

        String dvdRange;
        if(lastDVDCalculationDate == null)
            return true;
        
        Calendar lastCalculatedOn = Calendar.getInstance();
        lastCalculatedOn.setTime(lastDVDCalculationDate);
        
        Calendar currentTime = Calendar.getInstance();
        currentTime.setTimeInMillis(System.currentTimeMillis());
        
        if(lastCalculatedOn.get(Calendar.DAY_OF_MONTH) == currentTime
                .get(Calendar.DAY_OF_MONTH))
            return false;
        
        if (dataVolumePerDayCalculationRange.split("-").length < 1) {
            LOG.error("Location Deleter Service: Error calculating "
                    + "data volume per day, configuration "
                    + "Invalid data volume per day calculation range"
                    + "Using 23-0");
            dvdRange = "23-0" ;
        }
        else
            dvdRange = dataVolumePerDayCalculationRange;
        
        int min = Integer.parseInt(dvdRange
                .split("-")[0]);
        int max = Integer.parseInt(dvdRange
                .split("-")[1]);
        int now = currentTime.get(Calendar.HOUR_OF_DAY);
        
        if(now > min && now < max)
            return true;
        
        return false;
    }

    private void freeSpace(StorageSystemGroup group) {

        if (validateGroupForDeletion(group)) {
            int minTimeToKeepStudy = group.getMinTimeStudyNotAccessed();
            String minTimeToKeppStudyUnit = group
                    .getMinTimeStudyNotAccessedUnit();
            List<Instance> allInstancesDueDeleteOnGroup = (ArrayList<Instance>) 
                    locationManager.findInstancesDueDelete(minTimeToKeepStudy
                            , minTimeToKeppStudyUnit, group.getGroupID(), null, null);
            
                Map<String, List<Instance>> mapInstancesFoundOnGroupToStudy = 
                        getInstancesOnGroupPerStudyMap(allInstancesDueDeleteOnGroup, group);
                    for (String studyUID : mapInstancesFoundOnGroupToStudy
                            .keySet()) {
                        if (!group.isDeleteAsMuchAsPossible() 
                                && !needsFreeSpace(group))
                                break;
                    markCorrespondingStudyAndScheduleForDeletion(
                            studyUID,
                            group,
                            (ArrayList<Instance>) filterCopiesExist(
                                    (ArrayList<Instance>) mapInstancesFoundOnGroupToStudy
                                            .get(studyUID), group));
                }
            handleFailedToDeleteLocations(group);
        }
    }

    private void handleFailedToDeleteLocations(StorageSystemGroup group) {
        //handle failedToDeleteLocations
        LOG.info("Finding locations that previously failed deletions");
        List<Location> failedToDeleteLocations = (ArrayList<Location>)
                findFailedToDeleteLocations(group);
        try {
            locationManager.scheduleDelete(
                    failedToDeleteLocations, 1000, false);
        } catch (JMSException e) {
            LOG.error(
                    "Location Deleter Service: Failed to delete locations "
                    + "previously failing deletion - reason {}", e);
        }
    }

    private boolean needsFreeSpace(StorageSystemGroup group) {
        long thresholdInBytes = calculateExpectedDataVolumePerDay(group);
        for(String systemID : group.getStorageSystems().keySet()) {
            StorageSystem system = group.getStorageSystem(systemID);
            StorageSystemProvider provider = system
                    .getStorageSystemProvider(storageSystemProviders);
            if(provider == null) {
                LOG.info("Location Deleter Service : system {}'s "
                        + "has no configured provider, deletion "
                        + "will not apply", system);
                return false;
            }
            if(isUsableSystem(system)) {
                try {
                if(system.getMinFreeSpaceInBytes() == -1L)
                    system.setMinFreeSpaceInBytes(provider.getTotalSpace()
                            * Integer.parseInt(system.getMinFreeSpace()
                                    .replace("%", ""))/100);
                    if(provider.getUsableSpace() 
                            < system.getMinFreeSpaceInBytes() + thresholdInBytes) 
                    return true;
                } catch (IOException e) {
                    LOG.error("Location Deleter Service : "
                            + "failed to determine usable/total space on "
                            + "volume configured for system {} - reason {}"
                            , system, e);
                    return false;
                }
            }
        }
        return false;
    }

    private long calculateExpectedDataVolumePerDay(StorageSystemGroup group) {
        long dvdInBytes = calculateDataVolumePerDayInBytes(group.getGroupID());
        ThresholdInterval currentInterval = null;
        String deletionThreshold = group.getDeletionThreshold();
        List<ThresholdInterval> intervals =  
                createthresholdDurations(deletionThreshold, dvdInBytes);
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        for(ThresholdInterval interval : intervals){
            if(interval.end == 0)
                interval.end = 24;
            if(now >= interval.start && now < interval.end)
                currentInterval = interval;
        }
        return currentInterval.expectedInBytes;
    }

    private List<ThresholdInterval> createthresholdDurations(
            String deletionThreshold, long dvdInBytes) {
        List<ThresholdInterval> intervals = new ArrayList<ThresholdInterval>();
        if (deletionThreshold == null)
            return intervals;
        String[] thresholds = deletionThreshold.split(";");
        
        Arrays.sort(thresholds, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return Integer.parseInt(o1.split(":")[0]) < Integer.parseInt(o2
                        .split(":")[0]) ? -1
                        : Integer.parseInt(o1.split(":")[0]) == Integer
                                .parseInt(o2.split(":")[0]) ? 0 : 1;
                }
        });

        if (thresholds.length == 0)
            return intervals;
        int end = 0, start;
        for (int i = 0; i < thresholds.length; i++) {
            if (thresholds[i].contains(":"))
                if (i+1<thresholds.length
                        && thresholds[i + 1].contains(":")) {
                    end = Integer.parseInt(thresholds[i+1].split(":")[0]);
                }
                else {
                    end = 0;
                }
                    start = Integer.parseInt(thresholds[i].split(":")[0]);
                    long value = Long.parseLong(thresholds[0].split(":")[1]
                            .replaceAll("[GBgbmbMBhH]", ""));
                    long bytes = toValueInBytes(value,
                                    thresholds[i].split(":")[1].replaceAll("\\d+",
                                            ""), dvdInBytes);
                    ThresholdInterval newInterval = new ThresholdInterval(
                            start, end, bytes);
                    intervals.add(newInterval);
        }
        if(intervals.get(0).start != 0) {
            ThresholdInterval firstInterval = new ThresholdInterval(0,
                    intervals.get(0).start,
                    intervals.get(thresholds.length - 1).expectedInBytes);
            intervals.add(0, firstInterval);
        }
        return intervals;
    }

    private boolean isUsableSystem(StorageSystem system) {
        return !system.isReadOnly() 
                && system.getAvailability() != Availability.OFFLINE
                && system.getAvailability() != Availability.UNAVAILABLE;
    }

    private Map<String,List<Instance>> getInstancesOnGroupPerStudyMap(
            List<Instance> allInstancesDueDelete
            , StorageSystemGroup group) {

        Map<String,List<Instance>> instancesOnGroupPerStudyMap = new HashMap<String, List<Instance>>();
        
        if(allInstancesDueDelete == null)
            return instancesOnGroupPerStudyMap;
        
        for(Instance inst : allInstancesDueDelete) {
            
            String studyUID = inst.getSeries().getStudy().getStudyInstanceUID();
            if(!instancesOnGroupPerStudyMap.containsKey(studyUID))
                instancesOnGroupPerStudyMap.put(studyUID, new ArrayList<Instance>());
            for(Location loc : inst.getLocations()) {
                if(loc.getStorageSystemGroupID().compareTo(group.getGroupID()) == 0)
                    instancesOnGroupPerStudyMap.get(studyUID).add(inst);
            }
        }
        return instancesOnGroupPerStudyMap;
    }

    private synchronized void startPolling(int pollInterval) {
        if (deleteTask == null) {
            deleteTask = device.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    freeUpSpace();
                }
            }, pollInterval, pollInterval, TimeUnit.SECONDS);
            lastPollInterval = pollInterval;
            LOG.info(
                    "Location Deleter Service: started deletion task with interval {}s",
                    pollInterval);
        }
    }

    private synchronized void stopPolling() {
        if (deleteTask != null) {
            deleteTask.cancel(false);
            deleteTask = null;
            LOG.info("Location Deleter Service: stopped deletion task, last interval {}", lastPollInterval);
        }
    }

    private void markCorrespondingStudyAndScheduleForDeletion(
            String studyInstanceUID, StorageSystemGroup group,
            List<Instance> instancesDueDelete) {
        deletionRetries = group.getMaxDeleteServiceRetries();
        if (!instancesDueDelete.isEmpty())
            locationManager.markForDeletion(studyInstanceUID, group
                    .getGroupID());
            List<Instance> tmpInstancesScheduled = new ArrayList<Instance>();
            for(int i = 0; i<deletionRetries; i++) {
                instancesDueDelete.removeAll(tmpInstancesScheduled);
                tmpInstancesScheduled.clear();
            for (Instance inst : instancesDueDelete)
                try {
                    locationManager.scheduleDelete(locationManager.detachInstanceOnGroup(inst.getPk(), group.getGroupID()), 1000, true);
                    tmpInstancesScheduled.add(inst);
                } catch (JMSException e) {
                        LOG.error("Location Deleter Service: error scheduling "
                                + "deletion, attemting retry no {} - reason {}"
                                , i, e);
                    break;
                }
            }
    }

    private List<Location> findFailedToDeleteLocations(
            StorageSystemGroup group) {
        return  locationManager.findFailedToDeleteLocations(group);
    }

    private List<Instance> filterCopiesExist(
            List<Instance> instancesDueDeleteOnGroup, StorageSystemGroup group) {
        List<String> hasToBeOnSystems = Arrays.asList(group.getArchivedOnExternalSystems());
        List<String> hasToBeOnGroups = Arrays.asList(group.getArchivedOnGroups());
        List<Instance> filteredOnMany = new ArrayList<Instance>();
        boolean archivedAnyWhere = group.isArchivedAnyWhere();
        if(archivedAnyWhere) {
            return filterOneCopyExists(instancesDueDeleteOnGroup, group.getGroupID());
        }
        else {
        if( hasToBeOnSystems != null && !hasToBeOnSystems.isEmpty()) {
            filteredOnMany = filterOnExternalSystem(hasToBeOnSystems, instancesDueDeleteOnGroup);
        }
        if(hasToBeOnGroups != null && !hasToBeOnGroups.isEmpty()) {
                return filterOnGroups(hasToBeOnGroups,!filteredOnMany.isEmpty()
                        ? filteredOnMany : instancesDueDeleteOnGroup);
        }
        return filteredOnMany;
        }
    }

    private List<Instance> filterOnGroups(List<String> hasToBeOnGroups,
            List<Instance> filteredOnMany) {
        List<String> tmpFoundOnGroups = new ArrayList<String>();
        List<Instance> foundOnConfiguredGroups = new ArrayList<Instance>();
        for (Instance inst : filteredOnMany) {
            for (Location loc : inst.getLocations()) {
                if (!tmpFoundOnGroups.contains(loc.getStorageSystemGroupID()))
                    tmpFoundOnGroups.add(loc.getStorageSystemGroupID());
            }
            if (tmpFoundOnGroups.containsAll(hasToBeOnGroups))
                foundOnConfiguredGroups.add(inst);
        }
        return foundOnConfiguredGroups;
    }

    private List<Instance> filterOnExternalSystem(
            List<String> hasToBeOnSystems, List<Instance> filteredOnMany) {
        List<String> tmpFoundOnSystems = new ArrayList<String>();
        List<Instance> foundOnConfiguredSystems = new ArrayList<Instance>();
        for (Instance inst : filteredOnMany) {
            for (ExternalRetrieveLocation extLoc : inst.getExternalRetrieveLocations()) {
                if (!tmpFoundOnSystems.contains(extLoc.getRetrieveDeviceName()))
                    tmpFoundOnSystems.add(extLoc.getRetrieveDeviceName());
            }
            if (tmpFoundOnSystems.containsAll(hasToBeOnSystems))
                foundOnConfiguredSystems.add(inst);
        }
        return foundOnConfiguredSystems;
    }

    private List<Instance> filterOneCopyExists(
            List<Instance> instancesDueDeleteOnGroup, String  groupID) {
        List<Instance> foundOnAtleastOneGroup = new ArrayList<Instance>();
        for(Instance inst : instancesDueDeleteOnGroup) {
            if(inst.getExternalRetrieveLocations() != null 
                    && !inst.getExternalRetrieveLocations().isEmpty()) {
                foundOnAtleastOneGroup.add(inst);
                continue;
            }
            for(Location loc : inst.getLocations()) {
                if(!loc.isWithoutBulkData() 
                        && loc.getStorageSystemGroupID().compareTo(groupID) != 0) {
                    foundOnAtleastOneGroup.add(inst);
                }
            }
        }
        return foundOnAtleastOneGroup;
    }

    private boolean checkStudyRetentionContraints(StorageSystemGroup group) {
        return group.getMinTimeStudyNotAccessed() > 0
        && group.getMinTimeStudyNotAccessedUnit() != null;
    }

    private boolean checkDeletionConstraints(StorageSystemGroup group) {
        return group.getDeletionThreshold() != null
                || group.isDeleteAsMuchAsPossible();
    }

    private boolean checkArchivingConstraints(StorageSystemGroup group) {
        return group.getArchivedOnExternalSystems() != null
                || group.getArchivedOnGroups() != null
                || group.isArchivedAnyWhere();
    }

    private long toValueInBytes(long value, String unit, long dvdInBytes) {
        if ("GB".equalsIgnoreCase(unit))
            return value * 1000000000;
        if("GIB".equalsIgnoreCase(unit))
            return value * 125000000;
        else if ("MB".equalsIgnoreCase(unit))
            return value * 1000000;
        else if ("MIB".equalsIgnoreCase(unit))
            return value * 125000;
        else if ("KB".equalsIgnoreCase(unit))
            return value * 1000;
        else if ("KIB".equalsIgnoreCase(unit))
            return value * 125;
        else if ("H".equalsIgnoreCase(unit))
            return (dvdInBytes * value)/24;
        else if ("D".equalsIgnoreCase(unit))
            return dvdInBytes * value;
        else
            return value;
    }

    protected class ThresholdInterval {
        int start, end;
        long expectedInBytes;

        public ThresholdInterval(int start, int end, long bytes) {
            this.start = start;
            this.end = end;
            this.expectedInBytes = bytes;
        }
    }
}
