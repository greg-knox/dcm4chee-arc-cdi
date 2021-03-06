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
 * Portions created by the Initial Developer are Copyright (C) 2011
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
package org.dcm4chee.archive.wado;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.enterprise.context.RequestScoped;
import javax.enterprise.event.Event;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.image.PaletteColorModel;
import org.dcm4che3.image.PixelAspectRatio;
import org.dcm4che3.imageio.codec.ImageReaderFactory;
import org.dcm4che3.imageio.codec.ImageWriterFactory;
import org.dcm4che3.imageio.codec.ImageWriterFactory.ImageWriterParam;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che3.imageio.plugins.dcm.DicomMetaData;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.SAXWriter;
import org.dcm4che3.io.TemplatesCache;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.service.BasicCStoreSCUResp;
import org.dcm4che3.util.Property;
import org.dcm4che3.util.StringUtils;
import org.dcm4che3.ws.rs.MediaTypes;
import org.dcm4chee.archive.dto.ArchiveInstanceLocator;
import org.dcm4chee.archive.dto.GenericParticipant;
import org.dcm4chee.archive.dto.ServiceType;
import org.dcm4chee.archive.fetch.forward.FetchForwardCallBack;
import org.dcm4chee.archive.fetch.forward.FetchForwardService;
import org.dcm4chee.archive.retrieve.impl.RetrieveAfterSendEvent;
import org.dcm4chee.archive.rs.HostAECache;
import org.dcm4chee.archive.rs.HttpSource;
import org.dcm4chee.archive.store.scu.CStoreSCUContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Service implementing DICOM PS 3.18-2011 (WADO), URI based communication.
 * 
 * @see ftp://medical.nema.org/medical/dicom/2011/11_18pu.pdf
 * 
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Umberto Cappellini <umberto.cappellini@agfa.com>
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 */
@RequestScoped
@Path("/wado/{AETitle}")
public class WadoURI extends Wado {

    private static final Logger LOG = LoggerFactory.getLogger(WadoURI.class);

    @Inject
    private Event<RetrieveAfterSendEvent> retrieveEvent;

    @Inject
    private HostAECache aeCache;

    private CStoreSCUContext context;

    private static final int STATUS_NOT_IMPLEMENTED = 501;

    public enum Anonymize {
        yes
    }

    public enum Annotation {
        patient, technique
    }

    public static final class Strings {
        final String[] values;

        public Strings(String s) {
            values = StringUtils.split(s, ',');
        }
    }

    public static final class ContentTypes {
        final MediaType[] values;

        public ContentTypes(String s) {
            String[] ss = StringUtils.split(s, ',');
            values = new MediaType[ss.length];
            for (int i = 0; i < ss.length; i++)
                values[i] = MediaType.valueOf(ss[i]);
        }
    }

    public static final class Annotations {
        final Annotation[] values;

        public Annotations(String s) {
            String[] ss = StringUtils.split(s, ',');
            values = new Annotation[ss.length];
            for (int i = 0; i < ss.length; i++)
                values[i] = Annotation.valueOf(ss[i]);
        }
    }

    public static final class Region {
        final double left;
        final double top;
        final double right;
        final double bottom;

        public Region(String s) {
            String[] ss = StringUtils.split(s, ',');
            if (ss.length != 4)
                throw new IllegalArgumentException(s);
            left = Double.parseDouble(ss[0]);
            top = Double.parseDouble(ss[1]);
            right = Double.parseDouble(ss[2]);
            bottom = Double.parseDouble(ss[3]);
            if (left < 0. || right > 1. || top < 0. || bottom > 1.
                    || left >= right || top >= bottom)
                throw new IllegalArgumentException(s);
        }
    }

    @Context
    private HttpServletRequest request;

    @Context
    private HttpHeaders headers;

    @QueryParam("requestType")
    private String requestType;

    @QueryParam("studyUID")
    private String studyUID;

    @QueryParam("seriesUID")
    private String seriesUID;

    @QueryParam("objectUID")
    private String objectUID;

    @QueryParam("contentType")
    private ContentTypes contentType;

    @QueryParam("charset")
    private Strings charset;

    @QueryParam("anonymize")
    private Anonymize anonymize;

    @QueryParam("annotation")
    private Annotations annotation;

    @QueryParam("rows")
    private int rows;

    @QueryParam("columns")
    private int columns;

    @QueryParam("region")
    private Region region;

    @QueryParam("windowCenter")
    private float windowCenter;

    @QueryParam("windowWidth")
    private float windowWidth;

    @QueryParam("frameNumber")
    private int frameNumber;

    @QueryParam("imageQuality")
    private int imageQuality;

    @QueryParam("presentationUID")
    private String presentationUID;

    @QueryParam("presentationSeriesUID")
    private String presentationSeriesUID;

    @QueryParam("transferSyntax")
    private List<String> transferSyntax;
    
    @QueryParam("overlays")
    private boolean overlays;

    @Inject
    private FetchForwardService fetchForwardService;

    @GET
    public Response retrieve() throws WebApplicationException {

        List<ArchiveInstanceLocator> insts = new ArrayList<ArchiveInstanceLocator>();
        List<ArchiveInstanceLocator> instswarning = new ArrayList<ArchiveInstanceLocator>();
        List<ArchiveInstanceLocator> instscompleted = new ArrayList<ArchiveInstanceLocator>();
        List<ArchiveInstanceLocator> instsfailed = new ArrayList<ArchiveInstanceLocator>();

        try {
            
            ApplicationEntity sourceAE = aeCache
                    .findAE(new HttpSource(request));
            if (sourceAE == null) {
                LOG.info("Unable to find the mapped AE for this host or even"
                        + " the fallback AE, coercion will not be applied");
            }
            
            context = new CStoreSCUContext(arcAE.getApplicationEntity()
                    , sourceAE, ServiceType.WADOSERVICE);

            checkRequest();

            final List<ArchiveInstanceLocator> ref = retrieveService
                    .calculateMatches(studyUID, seriesUID, objectUID,
                            queryParam, false);

            if (ref == null || ref.size() == 0)
                throw new WebApplicationException(Status.NOT_FOUND);
            else
                insts.addAll(ref);

            if (ref.size() != 1) {
                instsfailed.addAll(ref);
                throw new WebApplicationException(Status.BAD_REQUEST);
            }
            //internal
            ArchiveInstanceLocator instance = null;
            Attributes attrs = null;
            Response resp = null;
            
            if(ref.get(0).getStorageSystem() != null) {
            instance = ref.get(0);
            attrs = (Attributes) instance.getObject();
            resp = retrieve(instscompleted, instsfailed, ref, instance, attrs);
            }
            
            //external
            else {
                //try to forwards
                ApplicationEntity forwardingAE = null;
                if ((forwardingAE = fetchForwardService.getprefersForwardingAE(
                        aetitle, ref)) != null) {
                    Response redirectResponse = fetchForwardService.redirectRequest(forwardingAE, ref, request.getQueryString());
                    if(redirectResponse.getStatus() != Status.CONFLICT.getStatusCode())
                        return redirectResponse;
                }
                //fetch
                FetchForwardCallBack fetchCallBack = new FetchForwardCallBack() {
                    
                    @Override
                    public void onFetch(Collection<ArchiveInstanceLocator> instances,
                            BasicCStoreSCUResp resp) {
                        ref.clear();
                        ref.addAll(instances);
                    }
                };

                    instsfailed = fetchForwardService.fetchForward(aetitle, ref, fetchCallBack, fetchCallBack);
                    instance = ref.get(0);
                    attrs = (Attributes) instance.getObject();
                resp = retrieve(instscompleted, instsfailed, ref, instance, attrs);
            
            }
            

            if(resp == null)
            throw new WebApplicationException(STATUS_NOT_IMPLEMENTED);
            else
                return resp;
        } finally {
            // audit
            retrieveEvent.fire(new RetrieveAfterSendEvent(
                    new GenericParticipant(request.getRemoteAddr(), request
                            .getRemoteUser()), new GenericParticipant(request
                            .getLocalAddr(), null), new GenericParticipant(
                            request.getRemoteAddr(), request.getRemoteUser()),
                    device, insts, instscompleted, instswarning, instsfailed));
        }
    }

    private Response retrieve(List<ArchiveInstanceLocator> instscompleted,
            List<ArchiveInstanceLocator> instsfailed,
            List<ArchiveInstanceLocator> ref, ArchiveInstanceLocator instance,
            Attributes attrs) {
        if(!instsfailed.isEmpty())
            throw new WebApplicationException(Status.CONFLICT);
        MediaType mediaType = selectMediaType(instance.tsuid,
                instance.cuid, attrs);
        if (!isAccepted(mediaType)) {
            instsfailed.addAll(ref);
            throw new WebApplicationException(Status.NOT_ACCEPTABLE);
        }

        if (mediaType == MediaTypes.APPLICATION_DICOM_TYPE) {
            instscompleted.addAll(ref);
            return retrieveNativeDicomObject(instance, attrs);
        }

        if (mediaType == MediaTypes.IMAGE_JPEG_TYPE) {
            instscompleted.addAll(ref);
            return retrieveJPEG(instance, attrs);
        }

        if (mediaType == MediaTypes.IMAGE_GIF_TYPE) {
            instscompleted.addAll(ref);
            return retrieveGIF(instance, attrs);
        }

        if (mediaType == MediaTypes.IMAGE_PNG_TYPE) {
            instscompleted.addAll(ref);
            return retrievePNG(instance, attrs);
        }
        if (mediaType.isCompatible(MediaTypes.APPLICATION_PDF_TYPE)
                || mediaType.isCompatible(MediaType.TEXT_XML_TYPE)
//                  || mediaType.isCompatible(MediaType.TEXT_PLAIN_TYPE)
                || mediaType.isCompatible(MediaTypes.TEXT_RTF_TYPE)) {
            instscompleted.addAll(ref);
            return retrievePDFXMLOrText(mediaType, instance);
        }
        if (mediaType == MediaType.TEXT_HTML_TYPE) {
            try {
                instscompleted.addAll(ref);
                return retrieveSRHTML(instance);
            } catch (TransformerConfigurationException e) {
                return retrieveNativeDicomObject(instance, attrs);
            }
        }
        return null;
    }

    private boolean isSupportedSR(String cuid) {
        for (String c_uid : arcAE.getWadoSupportedSRClasses()) {
            if (c_uid.equals(cuid))
                return true;
        }
        return false;
    }

    private void checkRequest() throws WebApplicationException {
        ApplicationEntity ae = device.getApplicationEntity(aetitle);
        if (ae == null || !ae.isInstalled())
            throw new WebApplicationException(Status.SERVICE_UNAVAILABLE);

        if (!"WADO".equals(requestType))
            throw new WebApplicationException(Status.BAD_REQUEST);
        if (studyUID == null || seriesUID == null || objectUID == null)
            throw new WebApplicationException(Status.BAD_REQUEST);

        boolean applicationDicom = false;
        if (contentType != null) {
            for (MediaType mediaType : contentType.values) {
                if (!isAccepted(mediaType))
                    throw new WebApplicationException(Status.BAD_REQUEST);
                if (mediaType.isCompatible(MediaTypes.APPLICATION_DICOM_TYPE))
                    applicationDicom = true;
            }
        }
        if (applicationDicom ? (annotation != null || rows != 0 || columns != 0
                || region != null || windowCenter != 0 || windowWidth != 0
                || frameNumber != 0 || imageQuality != 0
                || presentationUID != null || presentationSeriesUID != null)
                : (anonymize != null || !transferSyntax.isEmpty() || rows < 0
                        || columns < 0 || imageQuality < 0
                        || imageQuality > 100 || presentationUID != null
                        && presentationSeriesUID == null))
            throw new WebApplicationException(Status.BAD_REQUEST);
    }

    private boolean isAccepted(MediaType mediaType) {
        for (MediaType accepted : headers.getAcceptableMediaTypes())
            if (mediaType.isCompatible(accepted))
                return true;
        return false;
    }

    private Response retrieveSRHTML(final ArchiveInstanceLocator ref)
            throws TransformerConfigurationException {
        return Response.ok(new StreamingOutput() {
            @Override
            public void write(OutputStream out) throws IOException {
                BufferedOutputStream bout = new BufferedOutputStream(out);
                Templates templates = null;
                SAXTransformerFactory factory = (SAXTransformerFactory) TransformerFactory.newInstance();
                TransformerHandler th = null;
                try {
                    String uri = StringUtils.replaceSystemProperties(
                                    arcAE.getWadoSRTemplateURI());
                    templates = TemplatesCache.getDefault().get(uri);
                } catch (TransformerConfigurationException e) {
                    
                    LOG.error("Unable to apply transformation for SR - check archive AE Configuration",e);
                    
                }
                try {
                    th = factory.newTransformerHandler(templates);
                } catch (TransformerConfigurationException e2) {
                    LOG.error("Error configuring transformer handler - reason {}",e2);
                }
                Transformer tr = th.getTransformer();
                String wado = request.getRequestURL().toString();
                tr.setParameter("wadoURL", wado);
                SAXWriter w = null;
                th.setResult(new StreamResult(bout));
                w = new SAXWriter(th);
                Attributes data = readAttributes(ref);
                try {
                    w.write(data);
                } catch (SAXException e) {
                    LOG.error("Unable to write SR using defined template - reason {}",e);
                }
            }
        }, MediaType.TEXT_HTML_TYPE).build();
    }

    private Response retrievePDFXMLOrText(MediaType mediaType,
            final ArchiveInstanceLocator ref) {

        return Response.ok(new StreamingOutput() {

            @Override
            public void write(OutputStream out) throws IOException,
                    WebApplicationException {
                Attributes attrs = readAttributes(ref);
                try (BufferedOutputStream bOut = new BufferedOutputStream(out)) {
                    bOut.write(attrs.getBytes(Tag.EncapsulatedDocument));
                }
            }
        }, mediaType).build();
    }

    private Attributes readAttributes(ArchiveInstanceLocator ref) throws IOException {
        for (;;)
            try (DicomInputStream in = new DicomInputStream(
                    storescuService.getFile(ref).toFile())) {
                return  in.readDataset(-1, -1);
            } catch (IOException e) {
                LOG.info("Failed to read Data Set with iuid={} from {}@{}",
                        ref.iuid, ref.getFilePath(), ref.getStorageSystem(), e);
                ref = ref.getFallbackLocator();
                if (ref == null) {
                    throw e;
                }
                LOG.info("Try read Data Set from alternative location");
            }
    }

    private Response retrieveNativeDicomObject(ArchiveInstanceLocator ref,
            Attributes attrs) {
        String tsuid = selectTransferSyntax(ref.tsuid);
        MediaType mediaType = MediaType
                .valueOf("application/dicom;transfer-syntax=" + tsuid);
        return Response.ok(new DicomObjectOutput(ref, attrs, tsuid, context, storescuService), mediaType)
                .build();
    }

    private String selectTransferSyntax(String tsuid) {
        return transferSyntax.contains("*") || transferSyntax.contains(tsuid)
                || !ImageReaderFactory.canDecompress(tsuid) ? tsuid
                : UID.ExplicitVRLittleEndian;
    }

    private Response retrieveJPEG(final ArchiveInstanceLocator ref, final Attributes attrs) {

        return Response.ok(new StreamingOutput() {

            @Override
            public void write(OutputStream out) throws IOException,
                    WebApplicationException {
                BufferedImage bi = getBufferedImage(ref, attrs);
                ImageOutputStream imageOut = new MemoryCacheImageOutputStream(out);
                try {
                    writeImage("JPEG", bi, imageOut);
                } finally {
                    imageOut.close();
                }
            }
        }, MediaTypes.IMAGE_JPEG_TYPE).build();
    }

    private Response retrievePNG(final ArchiveInstanceLocator ref, final Attributes attrs) {

        return Response.ok(new StreamingOutput() {

            @Override
            public void write(OutputStream out) throws IOException,
                    WebApplicationException {
                BufferedImage bi = getBufferedImage(ref, attrs);
                ImageOutputStream imageOut = new MemoryCacheImageOutputStream(out);
                try {
                    writeImage("PNG", bi, imageOut);
                } finally {
                    imageOut.close();
                }
            }
        }, MediaTypes.IMAGE_PNG_TYPE).build();
    }

    private void writeImage(String format, BufferedImage bi, ImageOutputStream ios)
            throws IOException {
        ColorModel cm = bi.getColorModel();
        if (cm instanceof PaletteColorModel)
            bi = ((PaletteColorModel) cm).convertToIntDiscrete(bi.getData());
        ImageWriter imageWriter = (format.equalsIgnoreCase("JPEG")?
                ImageIO.getImageWritersByFormatName("JPEG").next():
                ImageIO.getImageWritersByFormatName("PNG").next());

        try {
            ImageWriteParam imageWriteParam = getImageWriterParam(imageWriter);
            imageWriter.setOutput(ios);
            imageWriter.write(null, new IIOImage(bi, null, null),
                    imageWriteParam);
        } finally {
            imageWriter.dispose();
        }
    }

    private BufferedImage getBufferedImage(ArchiveInstanceLocator ref,
            Attributes attrs) throws IOException {
        for (;;)
            try (ImageInputStream iis = createImageInputStream(ref)) {
                return readImage(iis, attrs);
            } catch (IOException e) {
                LOG.info("Failed to read image with iuid={} from {}@{}",
                        ref.iuid, ref.getFilePath(), ref.getStorageSystem(), e);
                ref = ref.getFallbackLocator();
                if (ref == null) {
                    throw e;
                }
                LOG.info("Try read image from alternative location");
            }
    }

    private Response retrieveGIF(final ArchiveInstanceLocator ref,
            final Attributes attrs) {

        final MediaType mediaType = MediaTypes.IMAGE_GIF_TYPE;
        return Response.ok(new StreamingOutput() {

            @Override
            public void write(OutputStream out) throws IOException,
                    WebApplicationException {
                ImageOutputStream imageOut = new MemoryCacheImageOutputStream(out);
                try {
                    if (attrs.getInt(Tag.NumberOfFrames, 1) == 1)
                    {
                        BufferedImage bi = getBufferedImage(ref, attrs);
                        writeGIF(bi, imageOut);
                    }
                    else
                    {
                        if (frameNumber != 0)
                        {
                            BufferedImage bi = getBufferedImage(ref, attrs);
                            writeGIF(bi, imageOut);
                        }
                        else
                        {
                            //return all frames as GIF sequence
                            List<BufferedImage> bis = getBufferedImages(ref, attrs);
                            writeGIFs(ref.tsuid, bis, imageOut);
                        }
                    }
                } finally {
                    imageOut.close();
                }
            }
        }, mediaType).build();
    }

    private List<BufferedImage> getBufferedImages(ArchiveInstanceLocator ref, Attributes attrs) throws IOException {
        for (;;)
            try {
                return readImages(ref, attrs);
            } catch (IOException e) {
                LOG.info("Failed to read images with iuid={} from {}@{}",
                        ref.iuid, ref.getFilePath(), ref.getStorageSystem(), e);
                ref = ref.getFallbackLocator();
                if (ref == null) {
                    throw e;
                }
                LOG.info("Try read from alternative location");
            }
    }

    private void writeGIFs(String tsuid, List<BufferedImage> bis, ImageOutputStream ios) throws IOException {
        ImageWriter imageWriter = ImageIO.getImageWritersByFormatName("GIF").next();
        try {
            ImageWriteParam imageWriteParam = getImageWriterParam(imageWriter);
            imageWriter.setOutput(ios);
            imageWriter.prepareWriteSequence(null);
            for (int i = 0; i < bis.size(); i++) {

                BufferedImage bi = bis.get(i);
                ColorModel cm = bi.getColorModel();
                if (cm instanceof PaletteColorModel) {
                    bi = ((PaletteColorModel) cm).convertToIntDiscrete(bi.getData());
                }

                IIOMetadata metadata = ImageWriterFactory.getImageWriterParam(tsuid) != null
                        ? getIIOMetadata(bi,imageWriter,imageWriteParam,ImageWriterFactory.getImageWriterParam(tsuid))
                        : imageWriter.getDefaultImageMetadata(new ImageTypeSpecifier(bi), imageWriteParam);
                //setGIFMetadata(metadata);
                imageWriter.writeToSequence(new IIOImage(bi, null, metadata),imageWriteParam);
            }
        } finally {
            imageWriter.dispose();
        }
    }

    private IIOMetadata getIIOMetadata(BufferedImage bi, ImageWriter imageWriter, ImageWriteParam imageWriteParam,
                                       ImageWriterParam imageWriterParam) {
        Property[] props = imageWriterParam.getIIOMetadata();
        IIOMetadata metadata = imageWriter.getDefaultImageMetadata(new ImageTypeSpecifier(bi), imageWriteParam);
        String metaFormatName = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode)
                metadata.getAsTree(metaFormatName);
        for(Property prop: props)
        {
            String nodeName = prop.getName().split(":")[0];
            String attributeName = prop.getName().split(":")[1];
            String value = (String) prop.getValue();
            IIOMetadataNode tmpNode = getNode(
                    root,nodeName);
            tmpNode.setAttribute(attributeName, value);
        }
        return metadata;
    }

    //returns a node in the Image Metadata
    private static IIOMetadataNode getNode(IIOMetadataNode rootNode, String nodeName) {
          int nNodes = rootNode.getLength();
          for (int i = 0; i < nNodes; i++) {
            if (rootNode.item(i).getNodeName().compareToIgnoreCase(nodeName)== 0) {
              return((IIOMetadataNode) rootNode.item(i));
            }
          }
          IIOMetadataNode node = new IIOMetadataNode(nodeName);
          rootNode.appendChild(node);
          return(node);
        }

    private List<BufferedImage> readImages(
            ArchiveInstanceLocator ref, Attributes attrs) throws IOException {
        List<BufferedImage> imageList = new ArrayList<BufferedImage>();
        ImageReader reader = getDicomImageReader();
        try ( ImageInputStream iis = createImageInputStream(ref)) {
            reader.setInput(iis);
            DicomMetaData metaData = (DicomMetaData) reader.getStreamMetadata();
            metaData.getAttributes().addAll(attrs);
            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            init(param);
            int numOfFrames = attrs.getInt(Tag.NumberOfFrames, 1);
            for(int i=0;i<numOfFrames;i++) {
                imageList.add(rescale(
                        reader.read(i, param),
                        metaData.getAttributes(),
                        param.getPresentationState()));
            }
        } finally {
            reader.dispose();
        }
        return imageList;
    }

    private ImageInputStream createImageInputStream(ArchiveInstanceLocator ref) throws IOException {
        return ImageIO.createImageInputStream(storescuService.getFile(ref).toFile());
    }

    private BufferedImage readImage(ImageInputStream iis, Attributes attrs)
            throws IOException {
        ImageReader reader = getDicomImageReader();
        try {
            reader.setInput(iis);
            DicomMetaData metaData = (DicomMetaData) reader.getStreamMetadata();
            metaData.getAttributes().addAll(attrs);
            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            init(param);
            return rescale(
                    reader.read(frameNumber > 0 ? frameNumber - 1 : 0, param),
                    metaData.getAttributes(), param.getPresentationState());
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader getDicomImageReader() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("DICOM");
        if (!readers.hasNext()) {
            ImageIO.scanForPlugins();
            readers = ImageIO.getImageReadersByFormatName("DICOM");
        }
        return readers.next();
    }

    private BufferedImage rescale(BufferedImage src, Attributes imgAttrs,
            Attributes psAttrs) {
        int r = rows;
        int c = columns;
        float sy = psAttrs != null ? PixelAspectRatio
                .forPresentationState(psAttrs) : PixelAspectRatio
                .forImage(imgAttrs);
        if (r == 0 && c == 0 && sy == 1f)
            return src;

        float sx = 1f;
        if (r != 0 || c != 0) {
            if (r != 0 && c != 0)
                if (r * src.getWidth() > c * src.getHeight() * sy)
                    r = 0;
                else
                    c = 0;
            sx = r != 0 ? r / (src.getHeight() * sy) : c / (float)src.getWidth();
            sy *= sx;
        }
        AffineTransformOp op = new AffineTransformOp(
                AffineTransform.getScaleInstance(sx, sy),
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(src,
                op.createCompatibleDestImage(src, src.getColorModel()));
    }

    private void init(DicomImageReadParam param)
            throws WebApplicationException, IOException {
        
        if(!request.getQueryString().contains("overlays"))
        overlays = arcAE.isWadoOverlayRendering();
        //set overlay activation mask
        param.setOverlayActivationMask(overlays?0xf:0x0);
        param.setWindowCenter(windowCenter);
        param.setWindowWidth(windowWidth);
        if (presentationUID != null) {
            List<ArchiveInstanceLocator> ref = retrieveService
                    .calculateMatches(studyUID, presentationSeriesUID,
                            presentationUID, queryParam, false);
            if (ref == null || ref.size() == 0)
                throw new WebApplicationException(Status.NOT_FOUND);

            if (ref.size() != 1)
                throw new WebApplicationException(Status.BAD_REQUEST);

            param.setPresentationState(readAttributes(ref.get(0)));
        }
    }

    private void writeGIF(BufferedImage bi, ImageOutputStream ios)
            throws IOException {
        ColorModel cm = bi.getColorModel();
        if (cm instanceof PaletteColorModel)
            bi = ((PaletteColorModel) cm).convertToIntDiscrete(bi.getData());
        ImageWriter imageWriter = ImageIO.getImageWritersByFormatName("GIF")
                .next();
        try {
            ImageWriteParam imageWriteParam = getImageWriterParam(imageWriter);
            imageWriter.setOutput(ios);
            imageWriter.write(null, new IIOImage(bi, null, null),
                    imageWriteParam);
        } finally {
            imageWriter.dispose();
        }
    }

    private ImageWriteParam getImageWriterParam(ImageWriter imageWriter) {
        ImageWriteParam imageWriteParam = imageWriter
                .getDefaultWriteParam();
        if (imageQuality > 0) {
            imageWriteParam
                    .setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            imageWriteParam.setCompressionQuality(imageQuality / 100f);
        }
        return imageWriteParam;
    }

    /**
     * Returns the media type specified in the contentType request parameter if
     * supported, otherwise returns the first of the supported media types.
     */
    private MediaType selectMediaType(String transferSyntaxUID,
            String sopClassUID, Attributes attrs) {

        List<MediaType> supportedMediaTypes = supportedMediaTypesOf(
                transferSyntaxUID, sopClassUID, attrs);

        if (contentType != null)
            for (MediaType requestedType : contentType.values)
                for (MediaType supportedType : supportedMediaTypes)
                    if (requestedType.isCompatible(supportedType))
                        return supportedType;
        return supportedMediaTypes.get(0);
    }

    public List<MediaType> supportedMediaTypesOf(String transferSyntaxUID,
            String sopClassUID, Attributes attrs) {
        List<MediaType> list = new ArrayList<MediaType>(4);
        if (attrs.contains(Tag.BitsAllocated)) {
            if (attrs.getInt(Tag.NumberOfFrames, 1) > 1) {
                list.add(MediaTypes.APPLICATION_DICOM_TYPE);
                MediaType mediaType;
                if (UID.MPEG2.equals(transferSyntaxUID)
                        || UID.MPEG2MainProfileHighLevel
                                .equals(transferSyntaxUID))
                    mediaType = MediaTypes.VIDEO_MPEG_TYPE;
                else if (UID.MPEG4AVCH264HighProfileLevel41
                        .equals(transferSyntaxUID)
                        || UID.MPEG4AVCH264BDCompatibleHighProfileLevel41
                                .equals(transferSyntaxUID))
                    mediaType = MediaTypes.VIDEO_MP4_TYPE;
                else{
                    mediaType = MediaTypes.IMAGE_JPEG_TYPE;
                    list.add(mediaType);
                    mediaType = MediaTypes.IMAGE_GIF_TYPE;
                }
                list.add(mediaType);
            } else {
                list.add(MediaTypes.IMAGE_JPEG_TYPE);
                list.add(MediaTypes.IMAGE_GIF_TYPE);
                list.add(MediaTypes.IMAGE_PNG_TYPE);
                list.add(MediaTypes.APPLICATION_DICOM_TYPE);
            }
        } else if (isSupportedSR(sopClassUID)) {
            list.add(MediaType.TEXT_HTML_TYPE);
            list.add(MediaType.TEXT_PLAIN_TYPE);
            list.add(MediaTypes.APPLICATION_DICOM_TYPE);
        } else {
            list.add(MediaTypes.APPLICATION_DICOM_TYPE);
            if (UID.EncapsulatedPDFStorage.equals(sopClassUID))
                list.add(MediaTypes.APPLICATION_PDF_TYPE);
            else if (UID.EncapsulatedCDAStorage.equals(sopClassUID))
                list.add(MediaType.TEXT_XML_TYPE);
            else{
                MediaType mimeType = MediaType.valueOf(attrs.getString(Tag.MIMETypeOfEncapsulatedDocument));
                if(mimeType !=null)
                list.add(mimeType);
            }
        }
        return list;
    }

}
