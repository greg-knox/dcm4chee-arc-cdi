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
 * Portions created by the Initial Developer are Copyright (C) 2011-2014
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

package org.dcm4chee.archive.store.impl;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.BulkDataDescriptor;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.io.SAXTransformer;
import org.dcm4che3.io.SAXTransformer.SetupTransformer;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.soundex.FuzzyStr;
import org.dcm4che3.util.DateUtils;
import org.dcm4che3.util.SafeClose;
import org.dcm4che3.util.StreamUtils;
import org.dcm4che3.util.TagUtils;
import org.dcm4chee.archive.code.CodeService;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.conf.ArchiveDeviceExtension;
import org.dcm4chee.archive.conf.AttributeFilter;
import org.dcm4chee.archive.conf.Entity;
import org.dcm4chee.archive.conf.StoreAction;
import org.dcm4chee.archive.conf.StoreParam;
import org.dcm4chee.archive.entity.Code;
import org.dcm4chee.archive.entity.ContentItem;
import org.dcm4chee.archive.entity.Location;
import org.dcm4chee.archive.entity.Instance;
import org.dcm4chee.archive.entity.Issuer;
import org.dcm4chee.archive.entity.Patient;
import org.dcm4chee.archive.entity.RequestAttributes;
import org.dcm4chee.archive.entity.Series;
import org.dcm4chee.archive.entity.Study;
import org.dcm4chee.archive.entity.Utils;
import org.dcm4chee.archive.entity.VerifyingObserver;
import org.dcm4chee.archive.locationmgmt.LocationMgmt;
import org.dcm4chee.archive.issuer.IssuerService;
import org.dcm4chee.archive.monitoring.api.Monitored;
import org.dcm4chee.archive.patient.PatientSelectorFactory;
import org.dcm4chee.archive.patient.PatientService;
import org.dcm4chee.archive.store.NewStudyCreated;
import org.dcm4chee.archive.store.StoreContext;
import org.dcm4chee.archive.store.StoreService;
import org.dcm4chee.archive.store.StoreSession;
import org.dcm4chee.archive.store.StoreSessionClosed;
import org.dcm4chee.storage.ObjectAlreadyExistsException;
import org.dcm4chee.storage.RetrieveContext;
import org.dcm4chee.storage.StorageContext;
import org.dcm4chee.storage.conf.StorageSystem;
import org.dcm4chee.storage.service.RetrieveService;
import org.dcm4chee.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 */
@ApplicationScoped
public class StoreServiceImpl implements StoreService {

    static Logger LOG = LoggerFactory.getLogger(StoreServiceImpl.class);

    @Inject
    private LocationMgmt locationManager;

    @Inject
    private StorageService storageService;

    @Inject
    private RetrieveService retrieveService;

    @Inject
    private PatientService patientService;

    @Inject
    private IssuerService issuerService;

    @Inject
    private CodeService codeService;

    @Inject
    private StoreServiceEJB storeServiceEJB;

    @Inject
    private Event<StoreContext> storeEvent;

    @Inject
    @NewStudyCreated
    private Event<String> newStudyCreatedEvent;

    @Inject
    @StoreSessionClosed
    private Event<StoreSession> storeSessionClosed;

    @Inject
    private Device device;

    private int[] storeFilters = null;

    @Override
    public StoreSession createStoreSession(StoreService storeService) {
        return new StoreSessionImpl(storeService);
    }

    @Override
    public void initStorageSystem(StoreSession session)
            throws DicomServiceException {
        ArchiveAEExtension arcAE = session.getArchiveAEExtension();
        String groupID = arcAE.getStorageSystemGroupID();
        StorageSystem storageSystem = storageService.selectStorageSystem(
                groupID, 0);
        if (storageSystem == null)
            throw new DicomServiceException(Status.OutOfResources,
                    "No writeable Storage System in Storage System Group "
                            + groupID);
        session.setStorageSystem(storageSystem);
        StorageSystem spoolStorageSystem = null;
        String spoolGroupID = storageSystem.getStorageSystemGroup().getSpoolStorageGroup();
        if(spoolGroupID!= null){
            spoolStorageSystem= storageService.selectStorageSystem(
                    spoolGroupID, 0);
        }
        session.setSpoolStorageSystem(spoolStorageSystem != null ? 
                spoolStorageSystem : storageSystem);
    }

    @Override
    public void initMetaDataStorageSystem(StoreSession session)
            throws DicomServiceException {
        ArchiveAEExtension arcAE = session.getArchiveAEExtension();
        String groupID = arcAE.getMetaDataStorageSystemGroupID();
        if (groupID != null) {
            StorageSystem storageSystem = storageService.selectStorageSystem(
                    groupID, 0);
            if (storageSystem == null)
                throw new DicomServiceException(Status.OutOfResources,
                        "No writeable Storage System in Storage System Group "
                                + groupID);
            session.setMetaDataStorageSystem(storageSystem);
        }
    }

    @Override
    public void initSpoolDirectory(StoreSession session)
            throws DicomServiceException {
        ArchiveAEExtension arcAE = session.getArchiveAEExtension();
        Path spoolDir = Paths.get(arcAE.getSpoolDirectoryPath());
        if (!spoolDir.isAbsolute()) {
            StorageSystem storageSystem = session.getSpoolStorageSystem();
            spoolDir = storageService.getBaseDirectory(storageSystem).resolve(
                    spoolDir);
        }
        try {
            Files.createDirectories(spoolDir);
            Path dir = Files.createTempDirectory(spoolDir, null);
            LOG.info("{}: M-WRITE spool directory - {}", session, dir);
            session.setSpoolDirectory(dir);
        } catch (IOException e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    @Override
    public StoreContext createStoreContext(StoreSession session) {
        return new StoreContextImpl(session);
    }

    @Override
    public void writeSpoolFile(StoreContext context, Attributes fmi,
            InputStream data) throws DicomServiceException {
        writeSpoolFile(context, fmi, null, data);
    }

    @Override
    public void writeSpoolFile(StoreContext context, Attributes fmi,
            Attributes attrs) throws DicomServiceException {
        writeSpoolFile(context, fmi, attrs, null);
        context.setTransferSyntax(fmi.getString(Tag.TransferSyntaxUID));
        context.setAttributes(attrs);
    }

    @Override
    public void onClose(StoreSession session) {
        deleteSpoolDirectory(session);
        storeSessionClosed.fire(session);
    }

    @Override
    public void cleanup(StoreContext context) {
        if (context.getFileRef() == null) {
            deleteFinalFile(context);
            deleteMetaData(context);
        }
    }

    private void deleteMetaData(StoreContext context) {
        String storagePath = context.getMetaDataStoragePath();
        if (storagePath != null) {
            try {
                StorageSystem storageSystem = context.getStoreSession()
                        .getMetaDataStorageSystem();
                storageService.deleteObject(
                        storageService.createStorageContext(storageSystem),
                        storagePath);
            } catch (IOException e) {
                LOG.warn("{}: Failed to delete meta data - {}",
                        context.getStoreSession(), storagePath, e);
            }
        }
    }

    private void deleteFinalFile(StoreContext context) {
        String storagePath = context.getStoragePath();
        if (storagePath != null) {
            try {
                storageService.deleteObject(context.getStorageContext(),
                        storagePath);
            } catch (IOException e) {
                LOG.warn("{}: Failed to delete final file - {}",
                        context.getStoreSession(), storagePath, e);
            }
        }
    }

    private void deleteSpoolDirectory(StoreSession session) {
        Path dir = session.getSpoolDirectory();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(dir)) {
            for (Path file : directory) {
                try {
                    Files.delete(file);
                    LOG.info("{}: M-DELETE spool file - {}", session, file);
                } catch (IOException e) {
                    LOG.warn("{}: Failed to M-DELETE spool file - {}", session,
                            file, e);
                }
            }
            Files.delete(dir);
            LOG.info("{}: M-DELETE spool directory - {}", session, dir);
        } catch (IOException e) {
            LOG.warn("{}: Failed to M-DELETE spool directory - {}", session,
                    dir, e);
        }
    }

    private void writeSpoolFile(StoreContext context, Attributes fmi,
            Attributes ds, InputStream in) throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        MessageDigest digest = session.getMessageDigest();
        try {
            context.setSpoolFile(spool(session, fmi, ds, in, ".dcm", digest));
            if (digest != null)
                context.setSpoolFileDigest(TagUtils.toHexString(digest.digest()));
        } catch (IOException e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    @Override
    public void parseSpoolFile(StoreContext context)
            throws DicomServiceException {
        Path path = context.getSpoolFile();
        try (DicomInputStream in = new DicomInputStream(path.toFile());) {
            in.setIncludeBulkData(IncludeBulkData.URI);
            Attributes fmi = in.readFileMetaInformation();
            Attributes ds = in.readDataset(-1, -1);
            context.setTransferSyntax(fmi != null ? fmi
                    .getString(Tag.TransferSyntaxUID)
                    : UID.ImplicitVRLittleEndian);
            context.setAttributes(ds);
        } catch (IOException e) {
            throw new DicomServiceException(DATA_SET_NOT_PARSEABLE);
        }
    }

    @Override
    public Path spool(StoreSession session, InputStream in, String suffix)
            throws IOException {
        return spool(session, null, null, in, suffix, null);
    }

    private Path spool(StoreSession session, Attributes fmi, Attributes ds,
            InputStream in, String suffix, MessageDigest digest)
            throws IOException {
        Path spoolDirectory = session.getSpoolDirectory();
        Path path = Files.createTempFile(spoolDirectory, null, suffix);
        OutputStream out = Files.newOutputStream(path);
        try {
            if (digest != null) {
                digest.reset();
                out = new DigestOutputStream(out, digest);
            }
            out = new BufferedOutputStream(out);
            if (fmi != null) {
                @SuppressWarnings("resource")
                DicomOutputStream dout = new DicomOutputStream(out,
                        UID.ExplicitVRLittleEndian);
                if (ds == null)
                    dout.writeFileMetaInformation(fmi);
                else
                    dout.writeDataset(fmi, ds);
                out = dout;
            }
            if (in instanceof PDVInputStream) {
                ((PDVInputStream) in).copyTo(out);
            } else if (in != null) {
                StreamUtils.copy(in, out);
            }
        } finally {
            SafeClose.close(out);
        }
        LOG.info("{}: M-WRITE spool file - {}", session, path);
        return path;
    }

    @Override
    public void store(StoreContext context) throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        try {
            service.storeMetaData(context);
            service.processFile(context);
            service.coerceAttributes(context);
            service.updateDB(context);
        } catch (DicomServiceException e) {
            context.setStoreAction(StoreAction.FAIL);
            context.setThrowable(e);
            throw e;
        } finally {
            service.fireStoreEvent(context);
            service.cleanup(context);
        }
    }

    @Override
    public void fireStoreEvent(StoreContext context) {
        storeEvent.fire(context);
    }

    /*
     * coerceAttributes applies a loaded XSL stylesheet on the keys if given
     * currently 15/4/2014 modifies date and time attributes in the keys per
     * request
     */
    @Override
    public void coerceAttributes(final StoreContext context)
            throws DicomServiceException {

        final StoreSession session = context.getStoreSession();
        ArchiveAEExtension arcAE = session.getArchiveAEExtension();
        Attributes attrs = context.getAttributes();
        try {
            Attributes modified = context.getCoercedOriginalAttributes();
            Templates tpl = session.getRemoteAET() != null ? arcAE
                    .getAttributeCoercionTemplates(
                            attrs.getString(Tag.SOPClassUID), Dimse.C_STORE_RQ,
                            TransferCapability.Role.SCP, session.getRemoteAET())
                    : null;
            if (tpl != null) {
                attrs.update(SAXTransformer.transform(attrs, tpl, false, false,
                        new SetupTransformer() {

                            @Override
                            public void setup(Transformer transformer) {
                                setParameters(transformer, session);
                            }
                        }), modified);
            }
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
        // store service time zone support moved to decorator

    }

    private void setParameters(Transformer tr, StoreSession session) {
        Date date = new Date();
        String currentDate = DateUtils.formatDA(null, date);
        String currentTime = DateUtils.formatTM(null, date);
        tr.setParameter("date", currentDate);
        tr.setParameter("time", currentTime);
        tr.setParameter("calling", session.getRemoteAET());
        tr.setParameter("called", session.getLocalAET());
    }

    @Override
    @Monitored(name="processFile")
    public void processFile(StoreContext context) throws DicomServiceException {
        try {
            StoreSession session = context.getStoreSession();
            StorageContext storageContext = storageService
                    .createStorageContext(session.getStorageSystem());
            Path source = context.getSpoolFile();
            context.setStorageContext(storageContext);
            context.setFinalFileDigest(context.getSpoolFileDigest());
            context.setFinalFileSize(Files.size(source));

            String origStoragePath = context.calcStoragePath();
            String storagePath = origStoragePath;
            int copies = 1;
            for (;;) {
                try {
                    storageService
                            .moveFile(storageContext, source, storagePath);
                    context.setStoragePath(storagePath);
                    return;
                } catch (ObjectAlreadyExistsException e) {
                    storagePath = origStoragePath + '.' + copies++;
                }
            }
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    @Override
    public void updateDB(StoreContext context) throws DicomServiceException {

        ArchiveDeviceExtension dE = context.getStoreSession().getDevice()
                .getDeviceExtension(ArchiveDeviceExtension.class);

        try {
            String nodbAttrsDigest = noDBAttsDigest(context.getStoragePath(),
                    context.getStoreSession());
            context.setNoDBAttsDigest(nodbAttrsDigest);
        } catch (IOException e1) {
            throw new DicomServiceException(Status.UnableToProcess, e1);
        }

        for (int i = 0; i <= dE.getUpdateDbRetries(); i++) {

            try {
                LOG.info("{}: try to updateDB, try nr. {}",
                        context.getStoreSession(), i);
                storeServiceEJB.updateDB(context);
                break;
            } catch (RuntimeException e) {
                if (i >= dE.getUpdateDbRetries()) // last try failed
                    throw new DicomServiceException(Status.UnableToProcess, e);
                else
                    LOG.warn("{}: Failed to updateDB, try nr. {}",
                            context.getStoreSession(), i, e);
            }
        }

        updateAttributes(context);
    }

    @Override
    public void updateDB(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Instance instance = service.findOrCreateInstance(em, context);
        context.setInstance(instance);
        // RESTORE action BLOCK
        if (context.getStoreAction() != StoreAction.IGNORE
                && context.getStoreAction() != StoreAction.UPDATEDB
                && context.getStoragePath() != null) {
            Collection<Location> locations = instance.getLocations(2);
            Location location = createLocation(em, context);
            locations.add(location);

            // update instance retrieveAET
            updateRetrieveAETs(session, instance);
            // availability update
            updateAvailability(session, instance);

            findOrCreateStudyOnStorageGroup(context);
            if (context.getMetaDataStoragePath() != null) {
                Location metaDataRef = createMetaDataRef(em, context);
                locations.add(metaDataRef);
            }
            context.setFileRef(location);
        }
    }

    private void findOrCreateStudyOnStorageGroup(StoreContext context) {
        locationManager.findOrCreateStudyOnStorageGroup(context.getInstance()
                .getSeries().getStudy(), context.getStoreSession()
                .getStorageSystem().getStorageSystemGroup().getGroupID());
    }

    private void updateRetrieveAETs(StoreSession session, Instance instance) {
        ArrayList<String> retrieveAETs = new ArrayList<String>();
        retrieveAETs.addAll(Arrays.asList(session.getStorageSystem()
                .getStorageSystemGroup().getRetrieveAETs()));

        for (String aet : instance.getRetrieveAETs())
            if (!retrieveAETs.contains(aet))
                retrieveAETs.add(aet);
        String[] retrieveAETsArray = new String[retrieveAETs.size()];
        instance.setRetrieveAETs(retrieveAETs.toArray(retrieveAETsArray));
    }

    private void updateAvailability(StoreSession session, Instance instance) {
        if (session.getStorageSystem().getAvailability().ordinal() < instance
                .getAvailability().ordinal())
            instance.setAvailability(session.getStorageSystem()
                    .getAvailability());
    }

    private void updateAttributes(StoreContext context) {
        Instance instance = context.getInstance();
        Series series = instance.getSeries();
        Study study = series.getStudy();
        Patient patient = study.getPatient();
        Attributes attrs = context.getAttributes();
        Attributes modified = new Attributes();
        attrs.update(patient.getAttributes(), modified);
        attrs.update(study.getAttributes(), modified);
        attrs.update(series.getAttributes(), modified);
        attrs.update(instance.getAttributes(), modified);
        if (!modified.isEmpty()) {
            modified.addAll(context.getCoercedOriginalAttributes());
            context.setCoercedOrginalAttributes(modified);
        }
        logCoercedAttributes(context);
    }

    private void logCoercedAttributes(StoreContext context) {
        StoreSession session = context.getStoreSession();
        Attributes attrs = context.getCoercedOriginalAttributes();
        if (!attrs.isEmpty()) {
            LOG.info("{}: Coerced Attributes:\n{}New Attributes:\n{}", session,
                    attrs,
                    new Attributes(context.getAttributes(), attrs.tags()));
        }
    }

    @Override
    public StoreAction instanceExists(EntityManager em, StoreContext context,
            Instance instance) throws DicomServiceException {
        StoreSession session = context.getStoreSession();

        Collection<Location> fileRefs = instance.getLocations();

        if (fileRefs.isEmpty())
            return StoreAction.RESTORE;

        if (context.getStoreSession().getArchiveAEExtension()
                .isIgnoreDuplicatesOnStorage())
            return StoreAction.IGNORE;

        if (!hasSameSourceAET(instance, session.getRemoteAET()))
            return StoreAction.IGNORE;

        if (hasFileRefWithDigest(fileRefs, context.getSpoolFileDigest()))
            return StoreAction.IGNORE;

        if (context.getStoreSession().getArchiveAEExtension()
                .isCheckNonDBAttributesOnStorage()
                && (hasFileRefWithOtherAttsDigest(fileRefs,
                        context.getNoDBAttsDigest())))
            return StoreAction.UPDATEDB;

        return StoreAction.REPLACE;
    }

    private boolean hasSameSourceAET(Instance instance, String remoteAET) {
        return remoteAET.equals(instance.getSeries().getSourceAET());
    }

    private boolean hasFileRefWithDigest(Collection<Location> fileRefs,
            String digest) {
        if (digest == null)
            return false;

        for (Location fileRef : fileRefs) {
            if (digest.equals(fileRef.getDigest()))
                return true;
        }
        return false;
    }

    private boolean hasFileRefWithOtherAttsDigest(
            Collection<Location> fileRefs, String digest) {
        if (digest == null)
            return false;

        for (Location fileRef : fileRefs) {
            if (digest.equals(fileRef.getOtherAttsDigest()))
                return true;
        }
        return false;
    }

    @Override
    public Instance findOrCreateInstance(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreParam storeParam = session.getStoreParam();
        StoreService service = session.getStoreService();
        Collection<Location> replaced = new ArrayList<Location>();

        try {

            Attributes attrs = context.getAttributes();
            Instance inst = em
                    .createNamedQuery(Instance.FIND_BY_SOP_INSTANCE_UID_EAGER,
                            Instance.class)
                    .setParameter(1, attrs.getString(Tag.SOPInstanceUID))
                    .getSingleResult();
            StoreAction action = service.instanceExists(em, context, inst);
            LOG.info("{}: {} already exists - {}", session, inst, action);
            context.setStoreAction(action);
            switch (action) {
            case RESTORE:
            case UPDATEDB:
                service.updateInstance(em, context, inst);
            case IGNORE:
                unmarkLocationsForDelete(inst, context);
                return inst;
            case REPLACE:
                for (Iterator<Location> iter = inst.getLocations().iterator(); iter
                        .hasNext();) {
                    Location fileRef = iter.next();
                    // no other instances referenced through alias table
                    if (fileRef.getInstances().size() == 1) {
                        // delete
                        replaced.add(fileRef);
                    } else {
                        // remove inst
                        fileRef.getInstances().remove(inst);
                    }
                    iter.remove();
                }
                em.remove(inst);
            }
        } catch (NoResultException e) {
            context.setStoreAction(StoreAction.STORE);
        } catch (DicomServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }

        Instance newInst = service.createInstance(em, context);

        // delete replaced
        try {
            if (replaced.size()>0)
                locationManager.scheduleDelete(replaced, 0,false);
        } catch (Exception e) {
            LOG.error("StoreService : Error deleting replaced location - {}", e);
        }
        return newInst;
    }

    @Override
    public Series findOrCreateSeries(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes attrs = context.getAttributes();
        try {
            Series series = em
                    .createNamedQuery(Series.FIND_BY_SERIES_INSTANCE_UID_EAGER,
                            Series.class)
                    .setParameter(1, attrs.getString(Tag.SeriesInstanceUID))
                    .getSingleResult();
            service.updateSeries(em, context, series);
            return series;
        } catch (NoResultException e) {
            return service.createSeries(em, context);
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    @Override
    public Study findOrCreateStudy(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes attrs = context.getAttributes();
        try {
            Study study = em
                    .createNamedQuery(Study.FIND_BY_STUDY_INSTANCE_UID_EAGER,
                            Study.class)
                    .setParameter(1, attrs.getString(Tag.StudyInstanceUID))
                    .getSingleResult();
            service.updateStudy(em, context, study);
            return study;
        } catch (NoResultException e) {
            return service.createStudy(em, context);
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    @Override
    public Patient findOrCreatePatient(EntityManager em, StoreContext context)
            throws DicomServiceException {
        try {
            // ArchiveAEExtension arcAE = context.getStoreSession()
            // .getArchiveAEExtension();
            // PatientSelector selector = arcAE.getPatientSelector();
            // System.out.println("Selector Class Name:"+selector.getPatientSelectorClassName());
            // for (String key :
            // selector.getPatientSelectorProperties().keySet())
            // System.out.println("Property:("+key+","+selector.getPatientSelectorProperties().get(key)+")");

            StoreSession session = context.getStoreSession();
            return patientService.updateOrCreatePatientOnCStore(context
                    .getAttributes(), PatientSelectorFactory
                    .createSelector(context.getStoreSession().getStoreParam()),
                    session.getStoreParam());
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    @Override
    public Study createStudy(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes attrs = context.getAttributes();
        StoreParam storeParam = session.getStoreParam();
        Study study = new Study();
        study.setPatient(service.findOrCreatePatient(em, context));
        study.setProcedureCodes(codeList(attrs, Tag.ProcedureCodeSequence));
        study.setAttributes(attrs, storeParam.getAttributeFilter(Entity.Study),
                storeParam.getFuzzyStr());
        study.setIssuerOfAccessionNumber(findOrCreateIssuer(attrs
                .getNestedDataset(Tag.IssuerOfAccessionNumberSequence)));
        em.persist(study);
        LOG.info("{}: Create {}", session, study);
        newStudyCreatedEvent.fire(study.getStudyInstanceUID());
        return study;
    }

    private Issuer findOrCreateIssuer(Attributes item) {
        return item != null ? issuerService.findOrCreate(new Issuer(item))
                : null;
    }

    @Override
    public Series createSeries(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes data = context.getAttributes();
        StoreParam storeParam = session.getStoreParam();
        Series series = new Series();
        series.setStudy(service.findOrCreateStudy(em, context));
        series.setInstitutionCode(singleCode(data, Tag.InstitutionCodeSequence));
        series.setRequestAttributes(createRequestAttributes(
                data.getSequence(Tag.RequestAttributesSequence),
                storeParam.getFuzzyStr(), series));
        series.setSourceAET(session.getRemoteAET());
        series.setAttributes(data,
                storeParam.getAttributeFilter(Entity.Series),
                storeParam.getFuzzyStr());
        em.persist(series);
        LOG.info("{}: Create {}", session, series);
        return series;
    }

    @Override
    public Instance createInstance(EntityManager em, StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes data = context.getAttributes();
        StoreParam storeParam = session.getStoreParam();
        Instance inst = new Instance();
        inst.setSeries(service.findOrCreateSeries(em, context));
        inst.setConceptNameCode(singleCode(data, Tag.ConceptNameCodeSequence));
        inst.setVerifyingObservers(createVerifyingObservers(
                data.getSequence(Tag.VerifyingObserverSequence),
                storeParam.getFuzzyStr(), inst));
        inst.setContentItems(createContentItems(
                data.getSequence(Tag.ContentSequence), inst));
        inst.setRetrieveAETs(session.getStorageSystem().getStorageSystemGroup()
                .getRetrieveAETs());
        inst.setAvailability(session.getStorageSystem().getAvailability());
        inst.setAttributes(data,
                storeParam.getAttributeFilter(Entity.Instance),
                storeParam.getFuzzyStr());
        em.persist(inst);
        LOG.info("{}: Create {}", session, inst);
        return inst;
    }

    private Location createLocation(EntityManager em, StoreContext context) {
        StoreSession session = context.getStoreSession();
        StorageSystem storageSystem = session.getStorageSystem();
        Location fileRef = new Location.Builder()
                .storageSystemGroupID(
                        storageSystem.getStorageSystemGroup().getGroupID())
                .storageSystemID(storageSystem.getStorageSystemID())
                .storagePath(context.getStoragePath())
                .digest(context.getFinalFileDigest())
                .otherAttsDigest(context.getNoDBAttsDigest())
                .size(context.getFinalFileSize())
                .transferSyntaxUID(context.getTransferSyntax())
                .timeZone(context.getSourceTimeZoneID()).build();
        em.persist(fileRef);
        LOG.info("{}: Create {}", session, fileRef);
        return fileRef;
    }

    private Location createMetaDataRef(EntityManager em, StoreContext context) {
        StoreSession session = context.getStoreSession();
        StorageSystem storageSystem = session.getMetaDataStorageSystem();
        long metadataFileSize = 0l;
        try {
            metadataFileSize = Files.size(Paths.get(storageSystem.getStorageSystemPath())
                    .resolve(context.getMetaDataStoragePath()));
        } catch (IOException e) {
            LOG.error("{}: Unable to calculate Metadata File Size, setting the Metadata "
                    + "size to 0 for instance {} ", session, context.getInstance());
        }
        Location fileRef = new Location.Builder()
                .storageSystemGroupID(
                        storageSystem.getStorageSystemGroup().getGroupID())
                .storageSystemID(storageSystem.getStorageSystemID())
                .storagePath(context.getMetaDataStoragePath())
                .size(metadataFileSize)
                .transferSyntaxUID(UID.ExplicitVRLittleEndian)
                .timeZone(context.getSourceTimeZoneID()).withoutBulkdata(true)
                .build();
        em.persist(fileRef);
        LOG.info("{}: Create {}", session, fileRef);
        return fileRef;
    }

    @Override
    public void updateStudy(EntityManager em, StoreContext context, Study study) {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes data = context.getAttributes();
        StoreParam storeParam = session.getStoreParam();
        study.clearQueryAttributes();
        AttributeFilter studyFilter = storeParam
                .getAttributeFilter(Entity.Study);
        Attributes studyAttrs = study.getAttributes();
        Attributes modified = new Attributes();
        // check if trashed
        if (isRejected(study)) {
            em.remove(study.getAttributesBlob());
            study.setAttributes(new Attributes(data), studyFilter,
                    storeParam.getFuzzyStr());
        } else {
            if (!context.isFetch()
                    && !session.getLocalAET().equalsIgnoreCase(
                            device.getDeviceExtension(
                                    ArchiveDeviceExtension.class)
                                    .getFetchAETitle())
                                    && studyAttrs.updateSelected(data, modified,
                    studyFilter.getCompleteSelection(data))) {
                study.setAttributes(studyAttrs, studyFilter,
                        storeParam.getFuzzyStr());
                LOG.info("{}: Update {}:\n{}\nmodified:\n{}", session, study,
                        studyAttrs, modified);
            }
        }
        if (!context.isFetch()
                && !session.getLocalAET().equalsIgnoreCase(
                        device.getDeviceExtension(
                                ArchiveDeviceExtension.class)
                                .getFetchAETitle()))
        service.updatePatient(em, context, study.getPatient());
    }

    @Override
    public void updatePatient(EntityManager em, StoreContext context,
            Patient patient) {
        StoreSession session = context.getStoreSession();
        patientService.updatePatientByCStore(patient, context.getAttributes(),
                session.getStoreParam());
    }

    @Override
    public void updateSeries(EntityManager em, StoreContext context,
            Series series) throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes data = context.getAttributes();
        StoreParam storeParam = session.getStoreParam();
        series.clearQueryAttributes();
        Attributes seriesAttrs = series.getAttributes();
        AttributeFilter seriesFilter = storeParam
                .getAttributeFilter(Entity.Series);
        Attributes modified = new Attributes();
        // check if trashed
        if (isRejected(series)) {
            em.remove(series.getAttributesBlob());
            series.setAttributes(new Attributes(data), seriesFilter,
                    storeParam.getFuzzyStr());
        } else {
            if (!context.isFetch()
                    && !session.getLocalAET().equalsIgnoreCase(
                            device.getDeviceExtension(
                                    ArchiveDeviceExtension.class)
                                    .getFetchAETitle())
                    && seriesAttrs.updateSelected(data, modified,
                            seriesFilter.getCompleteSelection(data))) {
                series.setAttributes(seriesAttrs, seriesFilter,
                        storeParam.getFuzzyStr());
                LOG.info("{}: Update {}:\n{}\nmodified:\n{}", session, series,
                        seriesAttrs, modified);
            }
        }
        service.updateStudy(em, context, series.getStudy());
    }

    @Override
    public void updateInstance(EntityManager em, StoreContext context,
            Instance inst) throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StoreService service = session.getStoreService();
        Attributes data = context.getAttributes();
        StoreParam storeParam = session.getStoreParam();
        Attributes instAttrs = inst.getAttributes();
        AttributeFilter instFilter = storeParam
                .getAttributeFilter(Entity.Instance);
        Attributes modified = new Attributes();
        if (!context.isFetch()
                && !session.getLocalAET().equalsIgnoreCase(
                        device.getDeviceExtension(
                                ArchiveDeviceExtension.class)
                                .getFetchAETitle())
                && instAttrs.updateSelected(data, modified,
                        instFilter.getCompleteSelection(data))) {
            inst.setAttributes(data, instFilter, storeParam.getFuzzyStr());
            LOG.info("{}: {}:\n{}\nmodified:\n{}", session, inst, instAttrs,
                    modified);
        }
        service.updateSeries(em, context, inst.getSeries());
    }

    private int[] getStoreFilters(Attributes attrs) {

        if (storeFilters == null) {

            ArchiveDeviceExtension dExt = device
                    .getDeviceExtension(ArchiveDeviceExtension.class);
            storeFilters = merge(
                    dExt.getAttributeFilter(Entity.Patient)
                            .getCompleteSelection(attrs),
                    dExt.getAttributeFilter(Entity.Study).getCompleteSelection(
                            attrs), dExt.getAttributeFilter(Entity.Series)
                            .getCompleteSelection(attrs), dExt
                            .getAttributeFilter(Entity.Instance)
                            .getCompleteSelection(attrs));
            Arrays.sort(storeFilters);
        }

        return storeFilters;
    }

    @Override
    public void storeMetaData(StoreContext context)
            throws DicomServiceException {
        StoreSession session = context.getStoreSession();
        StorageSystem storageSystem = session.getMetaDataStorageSystem();
        if (storageSystem == null)
            return;

        try {
            StorageContext storageContext = storageService
                    .createStorageContext(storageSystem);
            String origStoragePath = context.calcMetaDataStoragePath();
            String storagePath = origStoragePath;
            int copies = 1;
            for (;;) {
                try {
                    try (DicomOutputStream out = new DicomOutputStream(
                            storageService.openOutputStream(storageContext,
                                    storagePath), UID.ExplicitVRLittleEndian)) {
                        storeMetaDataTo(context.getAttributes(), out);
                    }
                    context.setMetaDataStoragePath(storagePath);
                    return;
                } catch (ObjectAlreadyExistsException e) {
                    storagePath = origStoragePath + '.' + copies++;
                }
            }
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    private void storeMetaDataTo(Attributes attrs, DicomOutputStream out)
            throws IOException {
        Attributes metaData = new Attributes(attrs.bigEndian(), attrs.size());
        metaData.addWithoutBulkData(attrs, BulkDataDescriptor.DEFAULT);
        out.writeDataset(
                metaData.createFileMetaInformation(UID.ExplicitVRLittleEndian),
                metaData);
    }

    public int[] merge(final int[]... arrays) {
        int size = 0;
        for (int[] a : arrays)
            size += a.length;

        int[] res = new int[size];

        int destPos = 0;
        for (int i = 0; i < arrays.length; i++) {
            if (i > 0)
                destPos += arrays[i - 1].length;
            int length = arrays[i].length;
            System.arraycopy(arrays[i], 0, res, destPos, length);
        }

        return res;
    }

    private Collection<RequestAttributes> createRequestAttributes(Sequence seq,
            FuzzyStr fuzzyStr, Series series) {
        if (seq == null || seq.isEmpty())
            return null;

        ArrayList<RequestAttributes> list = new ArrayList<RequestAttributes>(
                seq.size());
        for (Attributes item : seq) {
            RequestAttributes request = new RequestAttributes(
                    item,
                    findOrCreateIssuer(item
                            .getNestedDataset(Tag.IssuerOfAccessionNumberSequence)),
                    fuzzyStr);
            request.setSeries(series);
            list.add(request);
        }
        return list;
    }

    private Collection<VerifyingObserver> createVerifyingObservers(
            Sequence seq, FuzzyStr fuzzyStr, Instance instance) {
        if (seq == null || seq.isEmpty())
            return null;

        ArrayList<VerifyingObserver> list = new ArrayList<VerifyingObserver>(
                seq.size());
        for (Attributes item : seq) {
            VerifyingObserver observer = new VerifyingObserver(item, fuzzyStr);
            observer.setInstance(instance);
            list.add(observer);
        }
        return list;
    }

    private Collection<ContentItem> createContentItems(Sequence seq,
            Instance inst) {
        if (seq == null || seq.isEmpty())
            return null;

        Collection<ContentItem> list = new ArrayList<ContentItem>(seq.size());
        for (Attributes item : seq) {
            String type = item.getString(Tag.ValueType);
            ContentItem contentItem = null;
            if ("CODE".equals(type)) {
                contentItem = new ContentItem(item.getString(
                        Tag.RelationshipType).toUpperCase(), singleCode(item,
                        Tag.ConceptNameCodeSequence), singleCode(item,
                        Tag.ConceptCodeSequence));
                list.add(contentItem);
            } else if ("TEXT".equals(type)) {
                contentItem = new ContentItem(item.getString(
                        Tag.RelationshipType).toUpperCase(), singleCode(item,
                        Tag.ConceptNameCodeSequence), item.getString(
                        Tag.TextValue, "*"));
            }
            if (contentItem != null) {
                contentItem.setInstance(inst);
                list.add(contentItem);
            }
        }
        return list;
    }

    private Code singleCode(Attributes attrs, int seqTag) {
        Attributes item = attrs.getNestedDataset(seqTag);
        if (item != null)
            try {
                return codeService.findOrCreate(new Code(item));
            } catch (Exception e) {
                LOG.info("Illegal code item in Sequence {}:\n{}",
                        TagUtils.toString(seqTag), item);
            }
        return null;
    }

    private Collection<Code> codeList(Attributes attrs, int seqTag) {
        Sequence seq = attrs.getSequence(seqTag);
        if (seq == null || seq.isEmpty())
            return Collections.emptyList();

        ArrayList<Code> list = new ArrayList<Code>(seq.size());
        for (Attributes item : seq) {
            try {
                list.add(codeService.findOrCreate(new Code(item)));
            } catch (Exception e) {
                LOG.info("Illegal code item in Sequence {}:\n{}",
                        TagUtils.toString(seqTag), item);
            }
        }
        return list;
    }

    private boolean isRejected(Study study) {
        if(study.isRejected()) {
            study.setRejected(false);
            return true;
        }
        return false;
    }

    private boolean isRejected(Series series) {
        if(series.isRejected()) {
            series.setRejected(false);
            return true;
        }
        return false;
    }

    private void unmarkLocationsForDelete(Instance inst, StoreContext context) {
        for (Location loc : inst.getLocations()) {
            if (loc.getStatus() == Location.Status.DELETE_FAILED) {
                if (loc.getStorageSystemGroupID().compareTo(
                        context.getStoreSession().getArchiveAEExtension()
                                .getStorageSystemGroupID()) == 0) 
                    loc.setStatus(Location.Status.OK);
                else if(belongsToAnyOnline(loc))
                    loc.setStatus(Location.Status.OK);
                else
                    loc.setStatus(Location.Status.ARCHIVE_FAILED);
            }
        }
    }

    private boolean belongsToAnyOnline(Location loc) {
        for (ApplicationEntity ae : device.getApplicationEntities()) {
            ArchiveAEExtension arcAEExt = ae
                    .getAEExtension(ArchiveAEExtension.class);
            if (arcAEExt.getStorageSystemGroupID().compareTo(
                    loc.getStorageSystemGroupID()) == 0)
                return true;
        }
        return false;
    }

    /**
     * Given a reference to a stored object, retrieves it and calculates the
     * digest of all the attributes (including bulk data), not stored in the
     * database. This step is optionally skipped by configuration.
     */
    private String noDBAttsDigest(String path, StoreSession session)
            throws IOException {

        if (session.getArchiveAEExtension().isCheckNonDBAttributesOnStorage()) {

            // retrieves and parses the object
            RetrieveContext retrieveContext = retrieveService
                    .createRetrieveContext(session.getStorageSystem());
            InputStream stream = retrieveService.openInputStream(
                    retrieveContext, path);
            DicomInputStream dstream = new DicomInputStream(stream);
            dstream.setIncludeBulkData(IncludeBulkData.URI);
            Attributes attrs = dstream.readDataset(-1, -1);
            dstream.close();

            // selects attributes non stored in the db
            Attributes noDBAtts = new Attributes();
            noDBAtts.addNotSelected(attrs, getStoreFilters(attrs));

            return Utils.digestAttributes(noDBAtts, session.getMessageDigest());
        } else
            return null;
    }

}
