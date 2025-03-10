package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.dataaccess.DataAccess;
import edu.harvard.iq.dataverse.dataaccess.ImageThumbConverter;
import edu.harvard.iq.dataverse.dataaccess.StorageIO;
import edu.harvard.iq.dataverse.harvest.client.HarvestingClient;
import edu.harvard.iq.dataverse.ingest.IngestServiceBean;
import edu.harvard.iq.dataverse.search.SolrSearchResult;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.storageuse.StorageQuota;
import edu.harvard.iq.dataverse.storageuse.StorageUseServiceBean;
import edu.harvard.iq.dataverse.storageuse.UploadSessionQuotaLimit;
import edu.harvard.iq.dataverse.util.FileSortFieldAndOrder;
import edu.harvard.iq.dataverse.util.FileUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

/**
 *
 * @author Leonid Andreev
 * 
 */

@Stateless
@Named
public class DataFileServiceBean implements java.io.Serializable {
    
    private static final Logger logger = Logger.getLogger(DataFileServiceBean.class.getCanonicalName());
    @EJB
    DvObjectServiceBean dvObjectService;
    @EJB
    PermissionServiceBean permissionService;
    @EJB
    UserServiceBean userService; 
    @EJB
    SettingsServiceBean settingsService;
    
    @EJB 
    IngestServiceBean ingestService;

    @EJB EmbargoServiceBean embargoService;
    
    @EJB SystemConfig systemConfig;
    
    @EJB
    StorageUseServiceBean storageUseService; 
    
    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;
    
    // Assorted useful mime types:
    
    // 3rd-party and/or proprietary tabular data formasts that we know
    // how to ingest:
    
    private static final String MIME_TYPE_STATA = "application/x-stata";
    private static final String MIME_TYPE_STATA13 = "application/x-stata-13";
    private static final String MIME_TYPE_RDATA = "application/x-rlang-transport";
    private static final String MIME_TYPE_CSV   = "text/csv";
    private static final String MIME_TYPE_CSV_ALT = "text/comma-separated-values";
    private static final String MIME_TYPE_TSV   = "text/tsv";
    public static final String MIME_TYPE_TSV_ALT   = "text/tab-separated-values";
    private static final String MIME_TYPE_XLSX  = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_TYPE_SPSS_SAV = "application/x-spss-sav";
    private static final String MIME_TYPE_SPSS_POR = "application/x-spss-por";
    
    // Tabular data formats we don't know how to ingets, but still recognize
    // as "tabular data":
    // TODO: - add more to this list? -- L.A. 4.0 beta13
    
    private static final String MIME_TYPE_FIXED_FIELD = "text/x-fixed-field";
    private static final String MIME_TYPE_SAS_TRANSPORT = "application/x-sas-transport";
    private static final String MIME_TYPE_SAS_SYSTEM = "application/x-sas-system";
    
    // The following are the "control card/syntax" formats that we recognize 
    // as "code":
    
    private static final String MIME_TYPE_R_SYNTAX = "application/x-r-syntax";
    private static final String MIME_TYPE_STATA_SYNTAX = "text/x-stata-syntax";
    private static final String MIME_TYPE_SPSS_CCARD = "text/x-spss-syntax";
    private static final String MIME_TYPE_SAS_SYNTAX = "text/x-sas-syntax";

    // The types recognized as "documents":
    // TODO: there has to be more! -- L.A. 4.0 beta13
    
    private static final String MIME_TYPE_PLAIN_TEXT = "text/plain";
    private static final String MIME_TYPE_DOCUMENT_PDF = "application/pdf";
    private static final String MIME_TYPE_DOCUMENT_MSWORD = "application/msword";
    private static final String MIME_TYPE_DOCUMENT_MSEXCEL = "application/vnd.ms-excel";
    private static final String MIME_TYPE_DOCUMENT_MSWORD_OPENXML = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    
    // Supported Astrophysics formats: 
    // (only FITS at this point)
    
    private static final String MIME_TYPE_FITS  = "application/fits";

    // Network Data files: 
    // (only GRAPHML at this point): 
    
    private static final String MIME_TYPE_NETWORK_GRAPHML = "text/xml-graphml";
   
    // SHAPE file type: 
    // this is the only supported file type in the GEO DATA class:
        
    private static final String MIME_TYPE_ZIP   = "application/zip";
    
    private static final String MIME_TYPE_UNDETERMINED_DEFAULT = "application/octet-stream";
    private static final String MIME_TYPE_UNDETERMINED_BINARY = "application/binary";

    /**
     * Per https://en.wikipedia.org/wiki/Media_type#Vendor_tree just "dataverse"
     * should be fine.
     *
     * @todo Consider registering this at http://www.iana.org/form/media-types
     * or switch to "prs" which "includes media types created experimentally or
     * as part of products that are not distributed commercially" according to
     * the page URL above.
     */
    public static final String MIME_TYPE_PACKAGE_FILE = "application/vnd.dataverse.file-package";
    
    public DataFile find(Object pk) {
        return em.find(DataFile.class, pk);
    }   
    
    /*public DataFile findByMD5(String md5Value){
        if (md5Value == null){
            return null;
        }
        Query query = em.createQuery("select object(o) from DataFile as o where o.md5 =:md5Value order by o.id");
        query.setParameter("md5Value", md5Value);
        return (DataFile)query.getSingleResult();
        
    }*/
    
    public List<DataFile> findAll(List<Long> fileIds){
        List<DataFile> dataFiles = new ArrayList<>();

         for (Long fileId : fileIds){
             dataFiles.add(find(fileId));
         }

        return dataFiles;
    }

    public List<DataFile> findAll(String fileIdsAsString){
        ArrayList<Long> dataFileIds = new ArrayList<>();

        String[] fileIds = fileIdsAsString.split(",");
        for (String fId : fileIds){
            dataFileIds.add(Long.parseLong(fId));
        }

        return findAll(dataFileIds);
    }
    
    public DataFile findByGlobalId(String globalId) {
            return (DataFile) dvObjectService.findByGlobalId(globalId, DvObject.DType.DataFile);
    }

    public List<DataFile> findByCreatorId(Long creatorId) {
        return em.createNamedQuery("DataFile.findByCreatorId").setParameter("creatorId", creatorId).getResultList();
    }

    public List<DataFile> findByReleaseUserId(Long releaseUserId) {
        return em.createNamedQuery("DataFile.findByReleaseUserId").setParameter("releaseUserId", releaseUserId).getResultList();
    }

    public DataFile findReplacementFile(Long previousFileId){
        Query query = em.createQuery("select object(o) from DataFile as o where o.previousDataFileId = :previousFileId");
        query.setParameter("previousFileId", previousFileId);
        try {
            DataFile retVal = (DataFile)query.getSingleResult();
            return retVal;
        } catch(Exception ex) {
            return null;
        }
    }

    
    public DataFile findPreviousFile(DataFile df){
        TypedQuery<DataFile> query = em.createQuery("select o from DataFile o" + " WHERE o.id = :dataFileId", DataFile.class);
        query.setParameter("dataFileId", df.getPreviousDataFileId());
        try {
            DataFile retVal = query.getSingleResult();
            return retVal;
        } catch(Exception ex) {
            return null;
        }
    }
    
    public List<DataFile> findByDatasetId(Long studyId) {
        /* 
           Sure, we don't have *studies* any more, in 4.0; it's a tribute 
           to the past. -- L.A.
        */
        String qr = "select o from DataFile o where o.owner.id = :studyId order by o.id";
        return em.createQuery(qr, DataFile.class)
                .setParameter("studyId", studyId).getResultList();
    }
    
    /**
     * 
     * @param collectionId numeric id of the parent collection ("dataverse")
     * @return list of files in the datasets that are *direct* children of the collection specified
     * (i.e., no datafiles in sub-collections of this collection will be included)
     */
    public List<DataFile> findByDirectCollectionOwner(Long collectionId) {
        String queryString = "select f from DataFile f, Dataset d where f.owner.id = d.id and d.owner.id = :collectionId order by f.id";
        return em.createQuery(queryString, DataFile.class)
                .setParameter("collectionId", collectionId).getResultList();
    }
    
    public List<DataFile> findAllRelatedByRootDatafileId(Long datafileId) {
        /* 
         Get all files with the same root datafile id
         the first file has its own id as root so only one query needed.
        */
        String qr = "select o from DataFile o where o.rootDataFileId = :datafileId order by o.id";
        return em.createQuery(qr, DataFile.class)
                .setParameter("datafileId", datafileId).getResultList();
    }

    public DataFile findByStorageIdandDatasetVersion(String storageId, DatasetVersion dv) {
        try {
            Query query = em.createNativeQuery("select o.id from dvobject o, filemetadata m " +
                    "where o.storageidentifier = '" + storageId + "' and o.id = m.datafile_id and m.datasetversion_id = " +
                    dv.getId() + "");
            query.setMaxResults(1);
            if (query.getResultList().size() < 1) {
                return null;
            } else {
                return findCheapAndEasy((Long) query.getSingleResult());
                //Pretty sure the above return will always error due to a conversion error
                //I "reverted" my change because I ended up not using this, but here is the fix below --MAD
//                Integer qr = (Integer) query.getSingleResult();
//                return findCheapAndEasy(qr.longValue());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error finding datafile by storageID and DataSetVersion: " + e.getMessage());
            return null;
        }
    }
    
    public List<FileMetadata> findFileMetadataByDatasetVersionId(Long datasetVersionId, int maxResults, String userSuppliedSortField, String userSuppliedSortOrder) {
        FileSortFieldAndOrder sortFieldAndOrder = new FileSortFieldAndOrder(userSuppliedSortField, userSuppliedSortOrder);
        String sortField = sortFieldAndOrder.getSortField();
        String sortOrder = sortFieldAndOrder.getSortOrder();
        if (maxResults < 0) {
            // return all results if user asks for negative number of results
            maxResults = 0;
        }
        String qr = "select o from FileMetadata o where o.datasetVersion.id = :datasetVersionId order by o." + sortField + " " + sortOrder;
        return em.createQuery(qr, FileMetadata.class)
                    .setParameter("datasetVersionId", datasetVersionId)
                    .setMaxResults(maxResults)
                    .getResultList();
    }
    
    public List<FileMetadata> findFileMetadataByDatasetVersionIdLabelSearchTerm(Long datasetVersionId, String searchTerm, String userSuppliedSortField, String userSuppliedSortOrder){
        FileSortFieldAndOrder sortFieldAndOrder = new FileSortFieldAndOrder(userSuppliedSortField, userSuppliedSortOrder);

        String sortField = sortFieldAndOrder.getSortField();
        String sortOrder = sortFieldAndOrder.getSortOrder();
        String searchClause = "";
        if(searchTerm != null && !searchTerm.isEmpty()){
            searchClause = " and  (lower(o.label) like '%" + searchTerm.toLowerCase() + "%' or lower(o.description) like '%" + searchTerm.toLowerCase() + "%')";
        }
        
        String queryString = "select o from FileMetadata o where o.datasetVersion.id = :datasetVersionId"
                + searchClause
                + " order by o." + sortField + " " + sortOrder;
        return em.createQuery(queryString, FileMetadata.class) 
            .setParameter("datasetVersionId", datasetVersionId)
            .getResultList();
    }
    
    public List<Integer> findFileMetadataIdsByDatasetVersionIdLabelSearchTerm(Long datasetVersionId, String searchTerm, String userSuppliedSortField, String userSuppliedSortOrder){
        FileSortFieldAndOrder sortFieldAndOrder = new FileSortFieldAndOrder(userSuppliedSortField, userSuppliedSortOrder);
        
        searchTerm=searchTerm.trim();
        String sortField = sortFieldAndOrder.getSortField();
        String sortOrder = sortFieldAndOrder.getSortOrder();
        String searchClause = "";
        if(searchTerm != null && !searchTerm.isEmpty()){
            searchClause = " and  (lower(o.label) like '%" + searchTerm.toLowerCase() + "%' or lower(o.description) like '%" + searchTerm.toLowerCase() + "%')";
        }
        
        //the createNativeQuary takes persistant entities, which Integer.class is not,
        //which is causing the exception. Hence, this query does not need an Integer.class
        //as the second parameter. 
        return em.createNativeQuery("select o.id from FileMetadata o where o.datasetVersion_id = "  + datasetVersionId
                + searchClause
                + " order by o." + sortField + " " + sortOrder)
                .getResultList();
    }
    
    public List<Long> findDataFileIdsByDatasetVersionIdLabelSearchTerm(Long datasetVersionId, String searchTerm, String userSuppliedSortField, String userSuppliedSortOrder){
        FileSortFieldAndOrder sortFieldAndOrder = new FileSortFieldAndOrder(userSuppliedSortField, userSuppliedSortOrder);
        
        searchTerm=searchTerm.trim();
        String sortField = sortFieldAndOrder.getSortField();
        String sortOrder = sortFieldAndOrder.getSortOrder();
        String searchClause = "";
        if(searchTerm != null && !searchTerm.isEmpty()){
            searchClause = " and  (lower(o.label) like '%" + searchTerm.toLowerCase() + "%' or lower(o.description) like '%" + searchTerm.toLowerCase() + "%')";
        }
        
        return em.createNativeQuery("select o.datafile_id from FileMetadata o where o.datasetVersion_id = "  + datasetVersionId
                + searchClause
                + " order by o." + sortField + " " + sortOrder)
                .getResultList();
    }
    
    public List<FileMetadata> findFileMetadataByDatasetVersionIdLazy(Long datasetVersionId, int maxResults, String userSuppliedSortField, String userSuppliedSortOrder, int firstResult) {
        FileSortFieldAndOrder sortFieldAndOrder = new FileSortFieldAndOrder(userSuppliedSortField, userSuppliedSortOrder);
        String sortField = sortFieldAndOrder.getSortField();
        String sortOrder = sortFieldAndOrder.getSortOrder();

        if (maxResults < 0) {
            // return all results if user asks for negative number of results
            maxResults = 0;
        }
        return em.createQuery("select o from FileMetadata o where o.datasetVersion.id = :datasetVersionId order by o." + sortField + " " + sortOrder, FileMetadata.class)
                .setParameter("datasetVersionId", datasetVersionId)
                .setMaxResults(maxResults)
                .setFirstResult(firstResult)
                .getResultList();
    }
    
    public Long findCountByDatasetVersionId(Long datasetVersionId){
        return (Long) em.createNativeQuery("select count(*)  from FileMetadata fmd "
                + " where fmd.datasetVersion_id = " + datasetVersionId
                + ";").getSingleResult();
    }

    public FileMetadata findFileMetadata(Long fileMetadataId) {
        return em.find(FileMetadata.class, fileMetadataId);
    }
    
    public FileMetadata findFileMetadataByDatasetVersionIdAndDataFileId(Long datasetVersionId, Long dataFileId) {

        Query query = em.createQuery("select o from FileMetadata o where o.datasetVersion.id = :datasetVersionId  and o.dataFile.id = :dataFileId");
        query.setParameter("datasetVersionId", datasetVersionId);
        query.setParameter("dataFileId", dataFileId);
        try {
            FileMetadata retVal = (FileMetadata) query.getSingleResult();
            return retVal;
        } catch(Exception ex) {
            return null;
        }
    }

    public FileMetadata findMostRecentVersionFileIsIn(DataFile file) {
        if (file == null) {
            return null;
        }
        List<FileMetadata> fileMetadatas = file.getFileMetadatas();
        if (fileMetadatas == null || fileMetadatas.isEmpty()) {
            return null;
        } else {
            return fileMetadatas.get(0);
        }
    }
    
    public List<DataFile> findAllCheapAndEasy(String fileIdsAsString){ 
        //assumption is that the fileIds are separated by ','
        ArrayList <DataFile> dataFilesFound = new ArrayList<>();
        String[] fileIds = fileIdsAsString.split(",");
        DataFile df = this.findCheapAndEasy(Long.parseLong(fileIds[0]));
        if(df != null){
            dataFilesFound.add(df);
        }

        return dataFilesFound;
    }

    public DataFile findCheapAndEasy(Long id) {
        DataFile dataFile;

        Object[] result;

        try {
            result = (Object[]) em.createNativeQuery("SELECT t0.ID, t0.CREATEDATE, t0.INDEXTIME, t0.MODIFICATIONTIME, t0.PERMISSIONINDEXTIME, t0.PERMISSIONMODIFICATIONTIME, t0.PUBLICATIONDATE, t0.CREATOR_ID, t0.RELEASEUSER_ID, t0.PREVIEWIMAGEAVAILABLE, t1.CONTENTTYPE, t0.STORAGEIDENTIFIER, t1.FILESIZE, t1.INGESTSTATUS, t1.CHECKSUMVALUE, t1.RESTRICTED, t3.ID, t2.AUTHORITY, t2.IDENTIFIER, t1.CHECKSUMTYPE, t1.PREVIOUSDATAFILEID, t1.ROOTDATAFILEID, t0.AUTHORITY, T0.PROTOCOL, T0.IDENTIFIER, t2.PROTOCOL FROM DVOBJECT t0, DATAFILE t1, DVOBJECT t2, DATASET t3 WHERE ((t0.ID = " + id + ") AND (t0.OWNER_ID = t2.ID) AND (t2.ID = t3.ID) AND (t1.ID = t0.ID))").getSingleResult();
        } catch (Exception ex) {
            return null;
        }

        if (result == null) {
            return null;
        }

        Integer file_id = (Integer) result[0];

        dataFile = new DataFile();
        dataFile.setMergeable(false);

        dataFile.setId(file_id.longValue());

        Timestamp createDate = (Timestamp) result[1];
        Timestamp indexTime = (Timestamp) result[2];
        Timestamp modificationTime = (Timestamp) result[3];
        Timestamp permissionIndexTime = (Timestamp) result[4];
        Timestamp permissionModificationTime = (Timestamp) result[5];
        Timestamp publicationDate = (Timestamp) result[6];

        dataFile.setCreateDate(createDate);
        dataFile.setIndexTime(indexTime);
        dataFile.setModificationTime(modificationTime);
        dataFile.setPermissionIndexTime(permissionIndexTime);
        dataFile.setPermissionModificationTime(permissionModificationTime);
        dataFile.setPublicationDate(publicationDate);

        // no support for users yet!
        // (no need to - so far? -- L.A. 4.2.2) 
        /*
         Long creatorId = (Long) result[7];
         if (creatorId != null) {
         AuthenticatedUser creator = userMap.get(creatorId);
         if (creator == null) {
         creator = userService.find(creatorId);
         if (creator != null) {
         userMap.put(creatorId, creator);
         }
         }
         if (creator != null) {
         dataFile.setCreator(creator);
         }
         }

         Long releaseUserId = (Long) result[8];
         if (releaseUserId != null) {
         AuthenticatedUser releaseUser = userMap.get(releaseUserId);
         if (releaseUser == null) {
         releaseUser = userService.find(releaseUserId);
         if (releaseUser != null) {
         userMap.put(releaseUserId, releaseUser);
         }
         }
         if (releaseUser != null) {
         dataFile.setReleaseUser(releaseUser);
         }
         }
         */
        Boolean previewAvailable = (Boolean) result[9];
        if (previewAvailable != null) {
            dataFile.setPreviewImageAvailable(previewAvailable);
        }
        
        String contentType = (String) result[10];
        
        if (contentType != null) {
            dataFile.setContentType(contentType);
        }

        String storageIdentifier = (String) result[11];

        if (storageIdentifier != null) {
            dataFile.setStorageIdentifier(storageIdentifier);
        }

        Long fileSize = (Long) result[12];

        if (fileSize != null) {
            dataFile.setFilesize(fileSize);
        }

        if (result[13] != null) {
            String ingestStatusString = (String) result[13];
            dataFile.setIngestStatus(ingestStatusString.charAt(0));
        }

        String md5 = (String) result[14];

        if (md5 != null) {
            dataFile.setChecksumValue(md5);
        }

        Boolean restricted = (Boolean) result[15];
        if (restricted != null) {
            dataFile.setRestricted(restricted);
        }


        Dataset owner = new Dataset();

        
        // TODO: check for nulls
        owner.setId((Long)result[16]);
        owner.setAuthority((String)result[17]);
        owner.setIdentifier((String)result[18]);

        String checksumType = (String) result[19];
        if (checksumType != null) {
            try {
                // In the database we store "SHA1" rather than "SHA-1".
                DataFile.ChecksumType typeFromStringInDatabase = DataFile.ChecksumType.valueOf(checksumType);
                dataFile.setChecksumType(typeFromStringInDatabase);
            } catch (IllegalArgumentException ex) {
                logger.info("Exception trying to convert " + checksumType + " to enum: " + ex);
            }
        }
        
        Long previousDataFileId = (Long) result[20];
        if (previousDataFileId != null){
            dataFile.setPreviousDataFileId(previousDataFileId);
        }
        
        Long rootDataFileId = (Long) result[21];
        if (rootDataFileId != null){
            dataFile.setRootDataFileId(rootDataFileId);
        } 
        
        String authority = (String) result[22];
        if (authority != null) {
            dataFile.setAuthority(authority);
        }

        String protocol = (String) result[23];
        if (protocol != null) {
            dataFile.setProtocol(protocol);
        }

        String identifier = (String) result[24];
        if (identifier != null) {
            dataFile.setIdentifier(identifier);
        }
        
        owner.setProtocol((String) result[25]);
        
        dataFile.setOwner(owner);

        // If content type indicates it's tabular data, spend 2 extra queries 
        // looking up the data table and tabular tags objects:
        
        if (MIME_TYPE_TSV.equalsIgnoreCase(contentType) || MIME_TYPE_TSV_ALT.equalsIgnoreCase(contentType)) {
            Object[] dtResult;
            try {
                dtResult = (Object[]) em.createNativeQuery("SELECT ID, UNF, CASEQUANTITY, VARQUANTITY, ORIGINALFILEFORMAT, ORIGINALFILESIZE FROM dataTable WHERE DATAFILE_ID = " + id).getSingleResult();
            } catch (Exception ex) {
                dtResult = null;
            }
        
            if (dtResult != null) {
                DataTable dataTable = new DataTable(); 

                dataTable.setId(((Integer) dtResult[0]).longValue());
            
                dataTable.setUnf((String)dtResult[1]);
            
                dataTable.setCaseQuantity((Long)dtResult[2]);
            
                dataTable.setVarQuantity((Long)dtResult[3]);
            
                dataTable.setOriginalFileFormat((String)dtResult[4]);
                
                dataTable.setOriginalFileSize((Long)dtResult[5]);
                
                dataTable.setDataFile(dataFile);
                dataFile.setDataTable(dataTable);
                
                // tabular tags: 
                
                List<Object[]> tagResults;
                try {
                    tagResults = em.createNativeQuery("SELECT t.TYPE, t.DATAFILE_ID FROM DATAFILETAG t WHERE t.DATAFILE_ID = " + id).getResultList();
                } catch (Exception ex) {
                    logger.info("EXCEPTION looking up tags.");
                    tagResults = null;
                }
                
                if (tagResults != null) {
                    List<String> fileTagLabels = DataFileTag.listTags();
                    
                    for (Object[] tagResult : tagResults) {
                        Integer tagId = (Integer)tagResult[0];
                        DataFileTag tag = new DataFileTag();
                        tag.setTypeByLabel(fileTagLabels.get(tagId));
                        tag.setDataFile(dataFile);
                        dataFile.addTag(tag);
                    }
                }
            }
        }
        
        return dataFile;
    }
    
    private List<AuthenticatedUser> retrieveFileAccessRequesters(DataFile fileIn) {
        List<AuthenticatedUser> retList = new ArrayList<>();

        // List<Object> requesters = em.createNativeQuery("select authenticated_user_id
        // from fileaccessrequests where datafile_id =
        // "+fileIn.getId()).getResultList();
        TypedQuery<Long> typedQuery = em.createQuery("select f.user.id from FileAccessRequest f where f.dataFile.id = :file_id and f.requestState= :requestState", Long.class);
        typedQuery.setParameter("file_id", fileIn.getId());
        typedQuery.setParameter("requestState", FileAccessRequest.RequestState.CREATED);
        List<Long> requesters = typedQuery.getResultList();
        for (Long userId : requesters) {
            AuthenticatedUser user = userService.find(userId);
            if (user != null) {
                retList.add(user);
            }
        }

        return retList;
    }
    
    private List<FileMetadata> retrieveFileMetadataForVersion(Dataset dataset, DatasetVersion version, List<DataFile> dataFiles, Map<Long, Integer> filesMap, Map<Long, Integer> categoryMap) {
        List<FileMetadata> retList = new ArrayList<>();
        Map<Long, Set<Long>> categoryMetaMap = new HashMap<>();
        
        List<Object[]> categoryResults = em.createNativeQuery("select t0.filecategories_id, t0.filemetadatas_id from filemetadata_datafilecategory t0, filemetadata t1 where (t0.filemetadatas_id = t1.id) AND (t1.datasetversion_id = "+version.getId()+")").getResultList();
        int i = 0;
        for (Object[] result : categoryResults) {
            Long category_id = (Long) result[0];
            Long filemeta_id = (Long) result[1];
            if (categoryMetaMap.get(filemeta_id) == null) {
                categoryMetaMap.put(filemeta_id, new HashSet<>());
            }
            categoryMetaMap.get(filemeta_id).add(category_id);
            i++;
        }
        logger.fine("Retrieved and mapped "+i+" file categories attached to files in the version "+version.getId());
        
        List<Object[]> metadataResults = em.createNativeQuery("select id, datafile_id, DESCRIPTION, LABEL, RESTRICTED, DIRECTORYLABEL, prov_freeform from FileMetadata where datasetversion_id = "+version.getId() + " ORDER BY LABEL").getResultList();
        
        for (Object[] result : metadataResults) {
            Integer filemeta_id = (Integer) result[0];
            
            if (filemeta_id == null) {
                continue;
            }
            
            Long file_id = (Long) result[1];
            if (file_id == null) {
                continue;
            }
            
            Integer file_list_id = filesMap.get(file_id);
            if (file_list_id == null) {
                continue;
            }
            FileMetadata fileMetadata = new FileMetadata();
            fileMetadata.setId(filemeta_id.longValue());
            fileMetadata.setCategories(new LinkedList<>());

            if (categoryMetaMap.get(fileMetadata.getId()) != null) {
                for (Long cat_id : categoryMetaMap.get(fileMetadata.getId())) {
                    if (categoryMap.get(cat_id) != null) {
                        fileMetadata.getCategories().add(dataset.getCategories().get(categoryMap.get(cat_id)));
                    }
                }
            }

            fileMetadata.setDatasetVersion(version);
            
            // Link the FileMetadata object to the DataFile:
            fileMetadata.setDataFile(dataFiles.get(file_list_id));
            // ... and the DataFile back to the FileMetadata:
            fileMetadata.getDataFile().getFileMetadatas().add(fileMetadata);
            
            String description = (String) result[2]; 
            
            if (description != null) {
                fileMetadata.setDescription(description);
            }
            
            String label = (String) result[3];
            
            if (label != null) {
                fileMetadata.setLabel(label);
            }
                        
            Boolean restricted = (Boolean) result[4];
            if (restricted != null) {
                fileMetadata.setRestricted(restricted);
            }
            
            String dirLabel = (String) result[5];
            if (dirLabel != null){
                fileMetadata.setDirectoryLabel(dirLabel);
            }
            
            String provFreeForm = (String) result[6];
            if (provFreeForm != null){
                fileMetadata.setProvFreeForm(provFreeForm);
            }
                        
            retList.add(fileMetadata);
        }
        
        logger.fine("Retrieved "+retList.size()+" file metadatas for version "+version.getId()+" (inside the retrieveFileMetadataForVersion method).");
                
        
        /* 
            We no longer perform this sort here, just to keep this filemetadata
            list as identical as possible to when it's produced by the "traditional"
            EJB method. When it's necessary to have the filemetadatas sorted by 
            FileMetadata.compareByLabel, the DatasetVersion.getFileMetadatasSorted()
            method should be called. 
        
        Collections.sort(retList, FileMetadata.compareByLabel); */
        
        return retList; 
    }
    
    public List<DataFile> findIngestsInProgress() {
        if ( em.isOpen() ) {
            String qr = "select object(o) from DataFile as o where o.ingestStatus =:scheduledStatusCode or o.ingestStatus =:progressStatusCode order by o.id";
            return em.createQuery(qr, DataFile.class)
                .setParameter("scheduledStatusCode", DataFile.INGEST_STATUS_SCHEDULED)
                .setParameter("progressStatusCode", DataFile.INGEST_STATUS_INPROGRESS)
                .getResultList();
        } else {
            return Collections.emptyList();
        }
    }
    
    
    public DataTable findDataTableByFileId(Long fileId) {
        Query query = em.createQuery("select object(o) from DataTable as o where o.dataFile.id =:fileId order by o.id");
        query.setParameter("fileId", fileId);
        
        Object singleResult;
        
        try{
            return (DataTable)query.getSingleResult();
        }catch(NoResultException ex){
            return null;
        }
    }
    
    public List<DataFile> findAll() {
        return em.createQuery("select object(o) from DataFile as o order by o.id", DataFile.class).getResultList();
    }
    
    public DataFile save(DataFile dataFile) {

        if (dataFile.isMergeable()) {   
            DataFile savedDataFile = em.merge(dataFile);
            return savedDataFile;
        } else {
            throw new IllegalArgumentException("This DataFile object has been set to NOT MERGEABLE; please ensure a MERGEABLE object is passed to the save method.");
        } 
    }
    
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public DataFile saveInTransaction(DataFile dataFile) {

        if (dataFile.isMergeable()) {   
            DataFile savedDataFile = em.merge(dataFile);
            return savedDataFile;
        } else {
            throw new IllegalArgumentException("This DataFile object has been set to NOT MERGEABLE; please ensure a MERGEABLE object is passed to the save method.");
        } 
    }
    
    private void msg(String m){
        System.out.println(m);
    }
    private void dashes(){
        msg("----------------");
    }
    private void msgt(String m){
        dashes(); msg(m); dashes();
    }
    
    /*
        Make sure the file replace ids are set for a initial version 
        of a file
    
    */
    public DataFile setAndCheckFileReplaceAttributes(DataFile savedDataFile){
               
        // Is this the initial version of a file?
        
        if ((savedDataFile.getRootDataFileId() == null)||
                (savedDataFile.getRootDataFileId().equals(DataFile.ROOT_DATAFILE_ID_DEFAULT))){
            msg("yes, initial version");
 
           // YES!  Set the RootDataFileId to the Id
           savedDataFile.setRootDataFileId(savedDataFile.getId());
           
           // SAVE IT AGAIN!!!
           msg("yes, save again");
        
            return em.merge(savedDataFile);   
           
        }else{       
            // Looking Good Billy Ray! Feeling Good Louis!    
            msg("nope, looks ok");

            return savedDataFile;
        }
    }
    
    
    public Boolean isPreviouslyPublished(Long fileId){
        Query query = em.createQuery("select object(o) from FileMetadata as o where o.dataFile.id =:fileId");
        query.setParameter("fileId", fileId);
        List<?> retList = query.getResultList();
        return (retList.size() > 1);
    }
    
    public void deleteFromVersion( DatasetVersion d, DataFile f ) {
		em.createNamedQuery("DataFile.removeFromDatasetVersion")
			.setParameter("versionId", d.getId()).setParameter("fileId", f.getId())
				.executeUpdate();
    }

    /* 
     Convenience methods for merging and removingindividual file metadatas, 
     without touching the rest of the DataFile object:
    */
    
    public FileMetadata mergeFileMetadata(FileMetadata fileMetadata) {
        
        FileMetadata newFileMetadata = em.merge(fileMetadata);
        em.flush();
        
        // Set the initial value of the rootDataFileId
        //    (does nothing if it's already set)
        //DataFile updatedDataFile = setAndCheckFileReplaceAttributes(newFileMetadata.getDataFile());
               
        return newFileMetadata;
    }
    
    public void removeFileMetadata(FileMetadata fileMetadata) {
        msgt("removeFileMetadata: fileMetadata");
        FileMetadata mergedFM = em.merge(fileMetadata);
        em.remove(mergedFM);
    }
    
    /* 
     * Same, for DataTables:
    */
    
    public DataTable saveDataTable(DataTable dataTable) {
        DataTable merged = em.merge(dataTable);
        em.flush();
        return merged;
    }
    
    public List<DataFile> findHarvestedFilesByClient(HarvestingClient harvestingClient) {
        String qr = "SELECT d FROM DataFile d, DvObject o, Dataset s WHERE o.id = d.id AND o.owner.id = s.id AND s.harvestedFrom.id = :harvestingClientId";
        return em.createQuery(qr, DataFile.class)
            .setParameter("harvestingClientId", harvestingClient.getId())
            .getResultList();
    }
    
    /*moving to the fileutil*/
    
    public void generateStorageIdentifier(DataFile dataFile) {
        dataFile.setStorageIdentifier(generateStorageIdentifier());
    }
    
    public String generateStorageIdentifier() {
        
        UUID uid = UUID.randomUUID();
                
        logger.log(Level.FINE, "UUID value: {0}", uid.toString());
        
        // last 6 bytes, of the random UUID, in hex: 
        
        String hexRandom = uid.toString().substring(24);
        
        logger.log(Level.FINE, "UUID (last 6 bytes, 12 hex digits): {0}", hexRandom);
        
        String hexTimestamp = Long.toHexString(new Date().getTime());
        
        logger.log(Level.FINE, "(not UUID) timestamp in hex: {0}", hexTimestamp);
            
        String storageIdentifier = hexTimestamp + "-" + hexRandom;
        
        logger.log(Level.FINE, "timestamp/UUID hybrid: {0}", storageIdentifier);
        return storageIdentifier; 
    }
    
    public boolean isSpssPorFile (DataFile file) {
        return (file != null) ? MIME_TYPE_SPSS_POR.equalsIgnoreCase(file.getContentType()) : false;
    }
    
    public boolean isSpssSavFile (DataFile file) {
        return (file != null) ? MIME_TYPE_SPSS_SAV.equalsIgnoreCase(file.getContentType()) : false;
    }
    
    /*
    public boolean isSpssPorFile (FileMetadata fileMetadata) {
        if (fileMetadata != null && fileMetadata.getDataFile() != null) {
            return isSpssPorFile(fileMetadata.getDataFile());
        }
        return false; 
    }
    */
    
    /*
     * This method will return true if the thumbnail is *actually available* and
     * ready to be downloaded. (it will try to generate a thumbnail for supported
     * file types, if not yet available)
     */
    public boolean isThumbnailAvailable (DataFile file) {
        if (file == null) {
            return false; 
        } 

        // If this file already has the "thumbnail generated" flag set,
        // we'll just trust that:
        if (file.isPreviewImageAvailable()) {
            logger.fine("returning true");
            return true;
        }
        
        // If thumbnails are not even supported for this class of files, 
        // there's nothing to talk about:      
        if (!FileUtil.isThumbnailSupported(file)) {
            return false;
        }
        
        /*
         Checking the permission here was resulting in extra queries; 
         it is now the responsibility of the client - such as the DatasetPage - 
         to make sure the permission check out, before calling this method.
         (or *after* calling this method? - checking permissions costs db 
         queries; checking if the thumbnail is available may cost cpu time, if 
         it has to be generated on the fly - so you have to figure out which 
         is more important... 
        
        */
        
        file = this.find(file.getId());
        if (ImageThumbConverter.isThumbnailAvailable(file)) {
            file.setPreviewImageAvailable(true);
            this.save(file);
            return true;
        }
        file.setPreviewImageFail(true);
        file.setPreviewImageAvailable(false);
        this.save(file);
        return false;
    }

    
    /* 
     * Methods for identifying "classes" (groupings) of files by type:
    */
    
    public String getFileClassById (Long fileId) {
        DataFile file = find(fileId);
        
        if (file == null) {
            return null; 
        }
        
        return getFileThumbnailClass(file);
    }
    
    public String getFileThumbnailClass (DataFile file) {
        // there's no solr search facet for "package files", but
        // there is a special thumbnail icon:
        if (isFileClassPackage(file)) {
            return FileUtil.FILE_THUMBNAIL_CLASS_PACKAGE;
        }
        
        if (file != null) {
            String fileTypeFacet = FileUtil.getFacetFileType(file);
        
            if (fileTypeFacet != null && FileUtil.FILE_THUMBNAIL_CLASSES.containsKey(fileTypeFacet)) {
                return FileUtil.FILE_THUMBNAIL_CLASSES.get(fileTypeFacet);
            }
        }
        
        return FileUtil.FILE_THUMBNAIL_CLASS_OTHER;
    }
    
    
    
    public boolean isFileClassImage (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();

        // Some browsers (Chrome?) seem to identify FITS files as mime
        // type "image/fits" on upload; this is both incorrect (the official
        // mime type for FITS is "application/fits", and problematic: then
        // the file is identified as an image, and the page will attempt to 
        // generate a preview - which of course is going to fail...
        
        if (FileUtil.MIME_TYPE_FITSIMAGE.equalsIgnoreCase(contentType)) {
            return false;
        }
        // besides most image/* types, we can generate thumbnails for 
        // pdf and "world map" files:
        
        return (contentType != null && (contentType.toLowerCase().startsWith("image/")));
    }
    
    public boolean isFileClassAudio (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();
        
        // TODO: 
        // verify that there are no audio types that don't start with "audio/" - 
        //  some exotic mp[34]... ?
        
        return (contentType != null && (contentType.toLowerCase().startsWith("audio/")));    
    }
    
    public boolean isFileClassCode (DataFile file) {
        if (file == null) {
            return false;
        }
     
        String contentType = file.getContentType();
        
        // The following are the "control card/syntax" formats that we recognize 
        // as "code":
    
        return (MIME_TYPE_R_SYNTAX.equalsIgnoreCase(contentType)
            || MIME_TYPE_STATA_SYNTAX.equalsIgnoreCase(contentType) 
            || MIME_TYPE_SAS_SYNTAX.equalsIgnoreCase(contentType)
            || MIME_TYPE_SPSS_CCARD.equalsIgnoreCase(contentType));
        
    }
    
    public boolean isFileClassDocument (DataFile file) {
        if (file == null) {
            return false;
        }
        
        // "Documents": PDF, assorted MS docs, etc. 
        
        String contentType = file.getContentType();
        int scIndex = 0;
        if (contentType != null && (scIndex = contentType.indexOf(';')) > 0) {
            contentType = contentType.substring(0, scIndex);
        }
        
        return (MIME_TYPE_PLAIN_TEXT.equalsIgnoreCase(contentType)
            || MIME_TYPE_DOCUMENT_PDF.equalsIgnoreCase(contentType)
            || MIME_TYPE_DOCUMENT_MSWORD.equalsIgnoreCase(contentType)
            || MIME_TYPE_DOCUMENT_MSEXCEL.equalsIgnoreCase(contentType)
            || MIME_TYPE_DOCUMENT_MSWORD_OPENXML.equalsIgnoreCase(contentType));
        
    }
    
    public boolean isFileClassAstro (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();
       
        // The only known/supported "Astro" file type is FITS,
        // so far:
        
        return (MIME_TYPE_FITS.equalsIgnoreCase(contentType) || FileUtil.MIME_TYPE_FITSIMAGE.equalsIgnoreCase(contentType));
        
    }
    
    public boolean isFileClassNetwork (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();
       
        // The only known/supported Network Data type is GRAPHML,
        // so far:
        
        return MIME_TYPE_NETWORK_GRAPHML.equalsIgnoreCase(contentType);
        
    }
    
    /* 
     * we don't really need a method for "other" - 
     * it's "other" if it fails to identify as any specific class... 
     * (or do we?)
    public boolean isFileClassOther (DataFile file) {
        if (file == null) {
            return false;
        }
        
    }
    */
    
    public boolean isFileClassGeo (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();
       
        // The only known/supported Geo Data type is SHAPE,
        // so far:
        
        return FileUtil.MIME_TYPE_GEO_SHAPE.equalsIgnoreCase(contentType);
    }
    
    public boolean isFileClassTabularData (DataFile file) {
        if (file == null) {
            return false;
        }
        
        // "Tabular data" is EITHER an INGESTED tabular data file, i.e.
        // a file with a DataTable and DataVariables; or a DataFile 
        // of one of the many known tabular data formats - SPSS, Stata, etc.
        // that for one reason or another didn't get ingested: 
        
        if (file.isTabularData()) {
            return true; 
        }
        
        // The formats we know how to ingest: 
        if (FileUtil.canIngestAsTabular(file)) {
            return true;
        }
        
        String contentType = file.getContentType();
        
        // And these are the formats we DON'T know how to ingest, 
        // but nevertheless recognize as "tabular data":
        
        return (MIME_TYPE_TSV.equalsIgnoreCase(contentType)
            || MIME_TYPE_FIXED_FIELD.equalsIgnoreCase(contentType) 
            || MIME_TYPE_SAS_TRANSPORT.equalsIgnoreCase(contentType)
            || MIME_TYPE_SAS_SYSTEM.equalsIgnoreCase(contentType));
        
    }
    
    public boolean isFileClassVideo (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();
        
        // TODO: 
        // check if there are video types that don't start with "audio/" - 
        // some exotic application/... formats ?
        
        return (contentType != null && (contentType.toLowerCase().startsWith("video/")));    
        
    }
    
    public boolean isFileClassPackage (DataFile file) {
        if (file == null) {
            return false;
        }
        
        String contentType = file.getContentType();
       
        return MIME_TYPE_PACKAGE_FILE.equalsIgnoreCase(contentType);
    }
    
    public void populateFileSearchCard(SolrSearchResult solrSearchResult) {
        solrSearchResult.setEntity(this.findCheapAndEasy(solrSearchResult.getEntityId()));
    }
    
    public boolean hasBeenDeleted(DataFile df){
        Dataset dataset = df.getOwner();
        DatasetVersion dsv = dataset.getLatestVersion();
        
        return findFileMetadataByDatasetVersionIdAndDataFileId(dsv.getId(), df.getId()) == null;
        
    }
    
    /**
     * Is this a replacement file??
     * 
     * The indication of a previousDataFileId says that it is
     * 
     * @param df
     * @return
     */
    public boolean isReplacementFile(DataFile df) {

        if (df.getPreviousDataFileId() == null){
            return false;
        }else if (df.getPreviousDataFileId() < 1){
            String errMSg = "Stop! previousDataFileId should either be null or a number greater than 0";
            logger.severe(errMSg);
            return false;
            // blow up -- this shouldn't happen!
            //throw new FileReplaceException(errMSg);
        }else if (df.getPreviousDataFileId() > 0){
            return true;
        }
        return false;
    }  // end: isReplacementFile
    
    public List<Long> selectFilesWithMissingOriginalTypes() {
        Query query = em.createNativeQuery("SELECT f.id FROM datafile f, datatable t where t.datafile_id = f.id AND (t.originalfileformat='" + MIME_TYPE_TSV + "' OR t.originalfileformat IS NULL) ORDER BY f.id");
        
        try {
            return query.getResultList();
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }
    
    public List<Long> selectFilesWithMissingOriginalSizes() {
        Query query = em.createNativeQuery("SELECT f.id FROM datafile f, datatable t where t.datafile_id = f.id AND (t.originalfilesize IS NULL ) AND (t.originalfileformat IS NOT NULL) ORDER BY f.id");
        
        try {
            return query.getResultList();
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }
    

    /**
     * Check that a identifier entered by the user is unique (not currently used
     * for any other study in this Dataverse Network). Also check for duplicate
     * in the remote PID service if needed
     * @param datafileId
     * @param storageLocation
     * @return  {@code true} iff the global identifier is unique.
     */
    public void finalizeFileDelete(Long dataFileId, String storageLocation) throws IOException {
        // Verify that the DataFile no longer exists: 
        if (find(dataFileId) != null) {
            throw new IOException("Attempted to permanently delete a physical file still associated with an existing DvObject "
                    + "(id: " + dataFileId + ", location: " + storageLocation);
        }
        if(storageLocation == null || storageLocation.isBlank()) {
            throw new IOException("Attempted to delete a physical file with no location "
                    + "(id: " + dataFileId + ", location: " + storageLocation);
        }
        StorageIO<DvObject> directStorageAccess = DataAccess.getDirectStorageIO(storageLocation);
        directStorageAccess.delete();
    }
    
    public void finalizeFileDeletes(Map<Long, String> storageLocations) {
        storageLocations.keySet().stream().forEach((dataFileId) -> {
            String storageLocation = storageLocations.get(dataFileId);

            try {
                finalizeFileDelete(dataFileId, storageLocation);
            } catch (IOException ioex) {
                logger.warning("Failed to delete the physical file associated with the deleted datafile id="
                        + dataFileId + ", storage location: " + storageLocation);
            }
        });
    }
    
    public Map<Long, String> getPhysicalFilesToDelete(DatasetVersion datasetVersion) {
        return getPhysicalFilesToDelete(datasetVersion, false);
    }
    
    public Map<Long, String> getPhysicalFilesToDelete(DatasetVersion datasetVersion, boolean destroy) {
        // Gather the locations of the physical files associated with DRAFT
        // (unpublished) DataFiles (or ALL the DataFiles, if "destroy") in the 
        // DatasetVersion, that will need to be deleted once the 
        // DeleteDatasetVersionCommand execution has been finalized:

        return getPhysicalFilesToDelete(datasetVersion.getFileMetadatas(), destroy);
    }
    
    public Map<Long, String> getPhysicalFilesToDelete(List<FileMetadata> fileMetadatasToDelete) {
        return getPhysicalFilesToDelete(fileMetadatasToDelete, false);
    }
    
    public Map<Long, String> getPhysicalFilesToDelete(List<FileMetadata> fileMetadatasToDelete, boolean destroy) {
        Map<Long, String> deleteStorageLocations = new HashMap<>();

        Iterator<FileMetadata> dfIt = fileMetadatasToDelete.iterator();
        while (dfIt.hasNext()) {
            DataFile df = dfIt.next().getDataFile();

            if (destroy || !df.isReleased()) {

                String storageLocation = getPhysicalFileToDelete(df);
                if (storageLocation != null) {
                    deleteStorageLocations.put(df.getId(), storageLocation);
                }

            }
        }

        return deleteStorageLocations;
    }
  
    public Map<Long, String> getPhysicalFilesToDelete(Dataset dataset) {
        // Gather the locations of ALL the physical files associated with 
        // a DATASET that is being DESTROYED, that will need to be deleted
        // once the DestroyDataset command execution has been finalized. 
        // Once again, note that we are selecting all the files from the dataset
        // - not just drafts. 

        Map<Long, String> deleteStorageLocations = new HashMap<>();

        Iterator<DataFile> dfIt = dataset.getFiles().iterator();
        while (dfIt.hasNext()) {
            DataFile df = dfIt.next();

            String storageLocation = getPhysicalFileToDelete(df);
            if (storageLocation != null) {
                deleteStorageLocations.put(df.getId(), storageLocation);
            }

        }

        return deleteStorageLocations;
    }
    
    public String getPhysicalFileToDelete(DataFile dataFile) {
        try {
            StorageIO<DataFile> storageIO = dataFile.getStorageIO();
            return storageIO.getStorageLocation();

        } catch (IOException ioex) {
            // something potentially wrong with the physical file,
            // or connection to the physical storage? 
            // we don't care (?) - we'll still try to delete the datafile from the database.
        }
        return null;
    }
    
    public boolean isFoldersMetadataPresentInVersion(DatasetVersion datasetVersion) {
        Query query = em.createNativeQuery("SELECT id FROM fileMetadata WHERE datasetversion_id="+datasetVersion.getId()+" AND directoryLabel IS NOT null LIMIT 1");
        
        try {
            int count = query.getResultList().size();
            return count > 0;
        } catch (Exception ex) {
            return false;
        }
    }
    
    public boolean isActivelyEmbargoed(FileMetadata fm) {
        return FileUtil.isActivelyEmbargoed(fm);
    }

    public Embargo findEmbargo(Long id) {
        DataFile d = find(id);
        return d.getEmbargo();
    }

    public boolean isRetentionExpired(FileMetadata fm) {
        return FileUtil.isRetentionExpired(fm);
    }
    /**
     * Checks if the supplied DvObjectContainer (Dataset or Collection; although
     * only collection-level storage quotas are officially supported as of now)
     * has a quota configured, and if not, keeps checking if any of the direct
     * ancestor Collections further up have a configured quota. If it finds one, 
     * it will retrieve the current total content size for that specific ancestor 
     * dvObjectContainer and use it to define the quota limit for the upload
     * session in progress. 
     * 
     * @param parent - DvObjectContainer, Dataset or Collection
     * @return upload session size limit spec, or null if quota not defined on 
     * any of the ancestor DvObjectContainers
     */
    public UploadSessionQuotaLimit getUploadSessionQuotaLimit(DvObjectContainer parent) {
        DvObjectContainer testDvContainer = parent; 
        StorageQuota quota = testDvContainer.getStorageQuota();
        while (quota == null && testDvContainer.getOwner() != null) {
            testDvContainer = testDvContainer.getOwner();
            quota = testDvContainer.getStorageQuota();
            if (quota != null) {
                break;
            }
        }    
        if (quota == null || quota.getAllocation() == null) {
            return null; 
        }
        
        // Note that we are checking the recorded storage use not on the 
        // immediate parent necessarily, but on the specific ancestor 
        // DvObjectContainer on which the storage quota is defined:
        Long currentSize = storageUseService.findStorageSizeByDvContainerId(testDvContainer.getId()); 
        
        return new UploadSessionQuotaLimit(quota.getAllocation(), currentSize);
    }
}
