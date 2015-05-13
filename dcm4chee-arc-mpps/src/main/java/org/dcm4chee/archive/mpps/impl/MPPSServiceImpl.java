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
 * Portions created by the Initial Developer are Copyright (C) 2012
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

package org.dcm4chee.archive.mpps.impl;

import java.util.HashMap;
import java.util.List;

import javax.ejb.Stateless;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.xml.transform.Templates;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.SAXTransformer;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.service.BasicMPPSSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4chee.archive.code.CodeService;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.conf.Entity;
import org.dcm4chee.archive.conf.StoreParam;
import org.dcm4chee.archive.entity.Code;
import org.dcm4chee.archive.entity.Instance;
import org.dcm4chee.archive.entity.MPPS;
import org.dcm4chee.archive.entity.Patient;
import org.dcm4chee.archive.entity.Series;
import org.dcm4chee.archive.entity.Study;
import org.dcm4chee.archive.mpps.MPPSService;
import org.dcm4chee.archive.mpps.event.MPPSCreate;
import org.dcm4chee.archive.mpps.event.MPPSEvent;
import org.dcm4chee.archive.mpps.event.MPPSFinal;
import org.dcm4chee.archive.mpps.event.MPPSUpdate;
import org.dcm4chee.archive.patient.PatientSelectorFactory;
import org.dcm4chee.archive.patient.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 */
@Stateless
public class MPPSServiceImpl implements MPPSService {

    private static Logger LOG = LoggerFactory
            .getLogger(MPPSServiceImpl.class);

    @PersistenceContext(unitName="dcm4chee-arc")
    private EntityManager em;

    @Inject
    private PatientService patientService;

    @Inject
    private CodeService codeService;

    @Inject
    @MPPSCreate
    private Event<MPPSEvent> createMPPSEvent;

    @Inject
    @MPPSUpdate
    private Event<MPPSEvent> updateMPPSEvent;

    @Inject
    @MPPSFinal
    private Event<MPPSEvent> finalMPPSEvent;

    @Override
    public MPPS createPerformedProcedureStep(ArchiveAEExtension arcAE,
            String iuid, Attributes attrs, Patient patient,
            MPPSService service) throws DicomServiceException {
        try {
            find(iuid);
            throw new DicomServiceException(Status.DuplicateSOPinstance)
                .setUID(Tag.AffectedSOPInstanceUID, iuid);
        } catch (NoResultException e) {}
        StoreParam storeParam = arcAE.getStoreParam();
        if (patient == null) {
            patient = service.findOrCreatePatient(attrs, storeParam);
        }
        Attributes mppsAttrs = new Attributes(attrs.size());
        mppsAttrs.addNotSelected(attrs,
                storeParam.getAttributeFilter(Entity.Patient).getCompleteSelection(attrs));
        MPPS mpps = new MPPS();
        mpps.setSopInstanceUID(iuid);
        mpps.setAttributes(mppsAttrs);
        mpps.setPatient(patient);
        em.persist(mpps);
        return mpps;
    }

    @Override
    public MPPS updatePerformedProcedureStep(ArchiveAEExtension arcAE,
            String iuid, Attributes modified, MPPSService service)
                    throws DicomServiceException {
        MPPS pps;
        try {
            pps = find(iuid);
        } catch (NoResultException e) {
            throw new DicomServiceException(Status.NoSuchObjectInstance)
                .setUID(Tag.AffectedSOPInstanceUID, iuid);
        }
        if (pps.getStatus() != MPPS.Status.IN_PROGRESS)
            BasicMPPSSCP.mayNoLongerBeUpdated();

        Attributes attrs = pps.getAttributes();
        attrs.addAll(modified);
        pps.setAttributes(attrs);
        if (pps.getStatus() != MPPS.Status.IN_PROGRESS) {
            if (!attrs.containsValue(Tag.PerformedSeriesSequence))
                throw new DicomServiceException(Status.MissingAttributeValue)
                        .setAttributeIdentifierList(Tag.PerformedSeriesSequence);
        }

        if (pps.getStatus() == MPPS.Status.DISCONTINUED) {
            Attributes codeItem = attrs.getNestedDataset(
                    Tag.PerformedProcedureStepDiscontinuationReasonCodeSequence);
            if (codeItem != null) {
                Code code = codeService.findOrCreate(new Code(codeItem));
                pps.setDiscontinuationReasonCode(code);
                if (Utils.isIncorrectWorklistEntrySelected(pps, 
                        arcAE.getApplicationEntity().getDevice())) {
                    rejectIncorrectWorklistEntrySelected(pps);
                }
            }
        }
        return pps;
    }

    private void rejectIncorrectWorklistEntrySelected(MPPS pps) {
        HashMap<String,Attributes> map = new HashMap<String,Attributes>();
        for (Attributes seriesRef : pps.getAttributes()
                .getSequence(Tag.PerformedSeriesSequence)) {
            for (Attributes ref : seriesRef
                    .getSequence(Tag.ReferencedImageSequence)) {
                map.put(ref.getString(Tag.ReferencedSOPInstanceUID), ref);
            }
            for (Attributes ref : seriesRef
                    .getSequence(Tag.ReferencedNonImageCompositeSOPInstanceSequence)) {
                map.put(ref.getString(Tag.ReferencedSOPInstanceUID), ref);
            }
            List<Instance> insts = em
                    .createNamedQuery(Instance.FIND_BY_SERIES_INSTANCE_UID,
                            Instance.class)
                    .setParameter(1, seriesRef.getString(Tag.SeriesInstanceUID))
                    .getResultList();
            for (Instance inst : insts) {
                String iuid = inst.getSopInstanceUID();
                Attributes ref = map.get(iuid);
                if (ref != null) {
                    String cuid = inst.getSopClassUID();
                    String cuidInPPS = ref.getString(Tag.ReferencedSOPClassUID);
                    Series series = inst.getSeries();
                    Study study = series.getStudy();
                    if (!cuid.equals(cuidInPPS)) {
                        LOG.warn("SOP Class of received Instance[iuid={}, cuid={}] "
                                + "of Series[iuid={}] of Study[iuid={}] differs from"
                                + "SOP Class[cuid={}] referenced by MPPS[iuid={}]",
                                iuid, cuid, series.getSeriesInstanceUID(), 
                                study.getStudyInstanceUID(), cuidInPPS,
                                pps.getSopInstanceUID());
                    }
                    inst.setRejectionNoteCode(pps.getDiscontinuationReasonCode());
                    series.clearQueryAttributes();
                    study.clearQueryAttributes();
                    LOG.info("Reject Instance[pk={},iuid={}] by MPPS Discontinuation Reason - {}",
                            inst.getPk(), iuid,
                            pps.getDiscontinuationReasonCode());
                }
            }
            map.clear();
        }
    }

    private MPPS find(String sopInstanceUID) {
        return em.createNamedQuery(
                MPPS.FIND_BY_SOP_INSTANCE_UID,
                MPPS.class)
             .setParameter(1, sopInstanceUID)
             .getSingleResult();
    }

    @Override
    public Patient findOrCreatePatient(Attributes attrs, StoreParam storeParam)
            throws DicomServiceException {
        try {
            return patientService.updateOrCreatePatientOnMPPSNCreate(
                    attrs, PatientSelectorFactory.createSelector(storeParam), storeParam);
        } catch (Exception e) {
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public void coerceAttributes(Association as, Dimse dimse,
            Attributes attrs) throws DicomServiceException {
        try {
            ApplicationEntity ae = as.getApplicationEntity();
            ArchiveAEExtension arcAE = ae.getAEExtensionNotNull(ArchiveAEExtension.class);
            Templates tpl = arcAE.getAttributeCoercionTemplates(
                    UID.ModalityPerformedProcedureStepSOPClass,
                    dimse, TransferCapability.Role.SCP, 
                    as.getRemoteAET());
            if (tpl != null) {
                Attributes modified = new Attributes();
                attrs.update(SAXTransformer.transform(attrs, tpl, false, false),
                        modified);
            }
        } catch (Exception e) {
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public void fireCreateMPPSEvent(ApplicationEntity ae, Attributes data,
            MPPS mpps) {
        createMPPSEvent.fire(
                new MPPSEvent(ae, Dimse.N_CREATE_RQ, data, mpps));
    }

    @Override
    public void fireUpdateMPPSEvent(ApplicationEntity ae, Attributes data,
            MPPS mpps) {
        updateMPPSEvent.fire(
                new MPPSEvent(ae, Dimse.N_SET_RQ, data, mpps));
    }

    @Override
    public void fireFinalMPPSEvent(ApplicationEntity ae, Attributes data,
            MPPS mpps) {
        finalMPPSEvent.fire(
                new MPPSEvent(ae, Dimse.N_SET_RQ, data, mpps));
    }

}
