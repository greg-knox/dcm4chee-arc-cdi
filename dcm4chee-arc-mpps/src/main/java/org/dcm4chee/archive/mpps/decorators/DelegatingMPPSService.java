package org.dcm4chee.archive.mpps.decorators;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.conf.StoreParam;
import org.dcm4chee.archive.entity.MPPS;
import org.dcm4chee.archive.entity.Patient;
import org.dcm4chee.archive.mpps.MPPSService;
import org.dcm4chee.conf.decorators.DelegatingService;
import org.dcm4chee.conf.decorators.DelegatingServiceImpl;

@DelegatingService
public class DelegatingMPPSService extends DelegatingServiceImpl<MPPSService> implements MPPSService {

	@Override
	public MPPS createPerformedProcedureStep(ArchiveAEExtension arcAE, String sopInstanceUID, Attributes attrs,
			Patient patient, MPPSService service) throws DicomServiceException {
		return getNextDecorator().createPerformedProcedureStep(arcAE, sopInstanceUID, attrs, patient, service);
	}

	@Override
	public MPPS updatePerformedProcedureStep(ArchiveAEExtension arcAE,
			String iuid, Attributes attrs, MPPSService service)	throws DicomServiceException {
		return getNextDecorator().updatePerformedProcedureStep(arcAE, iuid, attrs, service);
	}

	@Override
	public Patient findOrCreatePatient(Attributes attrs, StoreParam storeParam)	throws DicomServiceException {
		return getNextDecorator().findOrCreatePatient(attrs, storeParam);
	}

	@Override
	public void coerceAttributes(Association as, Dimse dimse, Attributes attrs)	throws DicomServiceException {
		getNextDecorator().coerceAttributes(as, dimse, attrs);
	}

	@Override
	public void fireCreateMPPSEvent(ApplicationEntity ae, Attributes data, MPPS mpps) {
		getNextDecorator().fireCreateMPPSEvent(ae, data, mpps);
	}

	@Override
	public void fireUpdateMPPSEvent(ApplicationEntity ae, Attributes data, MPPS mpps) {
        getNextDecorator().fireUpdateMPPSEvent(ae, data, mpps);
	}

	@Override
	public void fireFinalMPPSEvent(ApplicationEntity ae, Attributes data, MPPS mpps) {
		getNextDecorator().fireFinalMPPSEvent(ae, data, mpps);
	}

}
