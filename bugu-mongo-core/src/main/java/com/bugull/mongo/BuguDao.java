/*
 * Copyright (c) www.bugull.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bugull.mongo;

import com.bugull.mongo.annotations.Default;
import com.bugull.mongo.annotations.Embed;
import com.bugull.mongo.annotations.EmbedList;
import com.bugull.mongo.annotations.EnsureIndex;
import com.bugull.mongo.annotations.Entity;
import com.bugull.mongo.annotations.Id;
import com.bugull.mongo.annotations.IdType;
import com.bugull.mongo.annotations.Property;
import com.bugull.mongo.annotations.Ref;
import com.bugull.mongo.annotations.RefList;
import com.bugull.mongo.annotations.SplitType;
import com.bugull.mongo.cache.FieldsCache;
import com.bugull.mongo.exception.IdException;
import com.bugull.mongo.listener.CascadeDeleteListener;
import com.bugull.mongo.listener.EntityListener;
import com.bugull.mongo.utils.IdUtil;
import com.bugull.mongo.utils.MapperUtil;
import com.bugull.mongo.utils.Operator;
import com.bugull.mongo.utils.ReferenceUtil;
import com.bugull.mongo.utils.SortUtil;
import com.bugull.mongo.utils.StringUtil;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The basic Dao class.
 * 
 * @author Frank Wen(xbwen@hotmail.com)
 */
@SuppressWarnings("unchecked")
public class BuguDao<T> {
    
    protected final static Logger logger = LogManager.getLogger(BuguDao.class.getName());
    
    protected DBCollection coll;
    protected Class<T> clazz;
    protected DBObject keys;  //non-lazy fields
    
    protected final List<EntityListener> listenerList = new ArrayList<EntityListener>();
    
    public BuguDao(Class<T> clazz){
        this.clazz = clazz;
        //init none-split collection
        Entity entity = clazz.getAnnotation(Entity.class);
        SplitType split = entity.split();
        if(split == SplitType.NONE){
            String name = MapperUtil.getEntityName(clazz);
            initCollection(name);
        }
        //for keys
        keys = getLazyFields();
        //for cascade delete
        if(needCascadeListener()){
            listenerList.add(new CascadeDeleteListener(clazz));
        }
    }
    
    private DBObject getLazyFields(){
        DBObject lazyKeys = new BasicDBObject();
        Field[] fields = FieldsCache.getInstance().get(clazz);
        for(Field field : fields){
            String fieldName = field.getName();
            Property property = field.getAnnotation(Property.class);
            if(property!=null && property.lazy()){
                String name = property.name();
                if(!name.equals(Default.NAME)){
                    fieldName = name;
                }
                lazyKeys.put(fieldName, 0);
                continue;
            }
            Embed embed = field.getAnnotation(Embed.class);
            if(embed!=null && embed.lazy()){
                String name = embed.name();
                if(!name.equals(Default.NAME)){
                    fieldName = name;
                }
                lazyKeys.put(fieldName, 0);
                continue;
            }
            EmbedList embedList = field.getAnnotation(EmbedList.class);
            if(embedList!=null && embedList.lazy()){
                String name = embedList.name();
                if(!name.equals(Default.NAME)){
                    fieldName = name;
                }
                lazyKeys.put(fieldName, 0);
                continue;
            }
        }
        return lazyKeys;
    }
    
    private boolean needCascadeListener(){
        boolean result = false;
        Field[] fields = FieldsCache.getInstance().get(clazz);
        for(Field f : fields){
            Ref ref = f.getAnnotation(Ref.class);
            if(ref!=null && ref.cascade().toUpperCase().indexOf(Default.CASCADE_DELETE)!=-1){
                result = true;
                break;
            }
            RefList refList = f.getAnnotation(RefList.class);
            if(refList!=null && refList.cascade().toUpperCase().indexOf(Default.CASCADE_DELETE)!=-1){
                result = true;
                break;
            }
        }
        return result;
    }
    
    private void initCollection(String name){
        DB db = BuguFramework.getInstance().getConnection().getDB();
        Entity entity = clazz.getAnnotation(Entity.class);
        //if capped
        if(entity.capped() && !db.collectionExists(name)){
            DBObject options = new BasicDBObject("capped", true);
            long capSize = entity.capSize();
            if(capSize != Default.CAP_SIZE){
                options.put("size", capSize);
            }
            long capMax = entity.capMax();
            if(capMax != Default.CAP_MAX){
                options.put("max", capMax);
            }
            coll = db.createCollection(name, options);
        }else{
            coll = db.getCollection(name);
        }
        //for @EnsureIndex
        EnsureIndex ei = clazz.getAnnotation(EnsureIndex.class);
        if(ei != null){
            List<DBIndex> list = getDBIndex(ei.value());
            for(DBIndex dbi : list){
                coll.createIndex(dbi.indexKeys, dbi.indexOptions);
            }
        }
    }
    
    private final class DBIndex{
        DBObject indexKeys;
        DBObject indexOptions;
    }
    
    private List<DBIndex> getDBIndex(String indexString){
        List<DBIndex> list = new ArrayList<DBIndex>();
        indexString = indexString.replaceAll("\\}[^{^}]+\\{", "};{");
        indexString = indexString.replaceAll("[{}'']", "");
        String[] items = indexString.split(";");
        for(String item : items){
            DBObject indexKeys = new BasicDBObject();
            DBObject indexOptions = new BasicDBObject("background", true);
            String[] arr = item.split(",");
            for(String s : arr){
                String[] kv = s.split(":");
                String k = kv[0].trim();
                String v = kv[1].trim();
                //note: the following check order can't be changed!
                if(v.equalsIgnoreCase("2d") || v.equalsIgnoreCase("text")){
                    indexKeys.put(k, v);
                }
                else if(k.equalsIgnoreCase("expireAfterSeconds")){
                    indexOptions.put(k, Integer.parseInt(v));
                }
                else if(v.equals("1") || v.equals("-1")){
                    indexKeys.put(k, Integer.parseInt(v));
                }
                else if(v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")){
                    indexOptions.put(k, Boolean.parseBoolean(v));
                }
                else if(k.equalsIgnoreCase("name")){
                    indexOptions.put(k, v);
                }
            }
            DBIndex dbi = new DBIndex();
            dbi.indexKeys = indexKeys;
            dbi.indexOptions = indexOptions;
            list.add(dbi);
        }
        return list;
    }
    
    /**
     * If collection is splitted by date, you have to set the date to check which collection is in use.
     * @param date 
     */
    public void setSplitSuffix(Date date){
        Entity entity = clazz.getAnnotation(Entity.class);
        SplitType split = entity.split();
        SimpleDateFormat sdf = null;
        switch(split){
            case DAILY:
                sdf = new SimpleDateFormat("yyyy-MM-dd");
                break;
            case MONTHLY:
                sdf = new SimpleDateFormat("yyyy-MM");
                break;
            case YEARLY:
                sdf = new SimpleDateFormat("yyyy");
                break;
            default:
                break;
        }
        if(sdf != null){
            String ext = sdf.format(date);
            String name = MapperUtil.getEntityName(clazz);
            initCollection(name + "-" + ext);
        }
    }
    
    /**
     * If collection is splitted by string, you have to set the string value to check which collection is in use.
     * @param s 
     */
    public void setSplitSuffix(String s){
        Entity entity = clazz.getAnnotation(Entity.class);
        SplitType split = entity.split();
        if(split == SplitType.STRING){
            String name = MapperUtil.getEntityName(clazz);
            initCollection(name + "-" + s);
        }
    }
    
    /**
     * The default write concern is ACKNOWLEDGED, you can change it.
     * @param writeConcern 
     */
    public void setWriteConcern(WriteConcern writeConcern){
        coll.setWriteConcern(writeConcern);
    }
    
    /**
     * The default read preference is PRIMARY, you can change it.
     * @param readPreference 
     */
    public void setReadPreference(ReadPreference readPreference) {
        coll.setReadPreference(readPreference);
    }
    
    public void addEntityListener(EntityListener listener){
        listenerList.add(listener);
    }
    
    /**
     * notify all listeners after an entity is inserted.
     * the listeners' process code is executed asynchronized.
     * @param entity the entity contains all fields' value.
     */
    public void notifyInserted(final BuguEntity entity){
        for(final EntityListener listener : listenerList){
            BuguFramework.getInstance().getExecutor().execute(new Runnable(){
                @Override
                public void run(){
                    listener.entityInserted(entity);
                }
            });
        }
    }
    
    /**
     * notify all listeners after an entity is updated.
     * the listeners' process code is executed asynchronized.
     * @param entity the entity contains all fields' value.
     */
    public void notifyUpdated(final BuguEntity entity){
        for(final EntityListener listener : listenerList){
            BuguFramework.getInstance().getExecutor().execute(new Runnable(){
                @Override
                public void run(){
                    listener.entityUpdated(entity);
                }
            });
        }
    }
    
    /**
     * notify all listeners after an entity is deleted.
     * the listeners' process code is executed asynchronized.
     * @param entity the entity contains all fields' value.
     */
    public void notifyDeleted(final BuguEntity entity){
        for(final EntityListener listener : listenerList){
            BuguFramework.getInstance().getExecutor().execute(new Runnable(){
                @Override
                public void run(){
                    listener.entityDeleted(entity);
                }
            });
        }
    }
    
    /**
     * Insert an entity to mongoDB.
     * @param t
     * @return 
     */
    public WriteResult insert(T t){
        DBObject dbo = MapperUtil.toDBObject(t);
        WriteResult wr = coll.insert(dbo);
        String id = dbo.get(Operator.ID).toString();
        BuguEntity ent = (BuguEntity)t;
        ent.setId(id);
        if(!listenerList.isEmpty()){
            notifyInserted(ent);
        }
        return wr;
    }
    
    /**
     * Batch insert.
     * @param list 
     * @return 
     */
    public WriteResult insert(List<T> list){
        List<DBObject> dboList = new ArrayList<DBObject>();
        for(T t : list){
            dboList.add(MapperUtil.toDBObject(t));
        }
        WriteResult wr = coll.insert(dboList);
        int len = dboList.size();
        for(int i=0; i<len; i++){
            String id = dboList.get(i).get(Operator.ID).toString();
            BuguEntity ent = (BuguEntity)(list.get(i));
            ent.setId(id);
        }
        if(!listenerList.isEmpty()){
            for(T t : list){
                notifyInserted((BuguEntity)t);
            }
        }
        return wr;
    }
    
    /**
     * Save an entity to mongoDB. 
     * If no id in it, then insert the entity.
     * Else, check the id type, to confirm do save or insert.
     * @param t 
     * @return 
     */
    public WriteResult save(T t){
        WriteResult wr;
        BuguEntity ent = (BuguEntity)t;
        if(StringUtil.isEmpty(ent.getId())){
            wr = insert(t);
        }
        else{
            Field idField = null;
            try{
                idField = FieldsCache.getInstance().getIdField(clazz);
            }catch(IdException ex){
                logger.error(ex.getMessage(), ex);
            }
            Id idAnnotation = idField.getAnnotation(Id.class);
            if(idAnnotation.type()==IdType.USER_DEFINE){
                if(this.exists(Operator.ID, ent.getId())){
                    wr = doSave(ent);
                }else{
                    wr = insert(t);
                }
            }
            else{
                wr = doSave(ent);
            }
        }
        return wr;
    }
    
    private WriteResult doSave(BuguEntity ent){
        if(!listenerList.isEmpty()){
            notifyUpdated(ent);
        }
        return coll.save(MapperUtil.toDBObject(ent));
    }
    
    /**
     * Drop the collection. 
     * It will automatically drop all indexes from this collection.
     */
    public void drop(){
        if(!listenerList.isEmpty()){
            List<T> list = findAll();
            for(T t : list){
                remove(t);
            }
        }
        else{
            coll.drop();
            coll.dropIndexes();
        }
    }
    
    /**
     * Remove an entity.
     * @param t 
     * @return 
     */
    public WriteResult remove(T t){
        BuguEntity ent = (BuguEntity)t;
        return remove(ent.getId());
    }

    /**
     * Remove an entity by id.
     * @param id 
     * @return 
     */
    public WriteResult remove(String id){
        if(!listenerList.isEmpty()){
            BuguEntity entity = (BuguEntity)findOne(id);
            notifyDeleted(entity);
        }
        DBObject query = new BasicDBObject(Operator.ID, IdUtil.toDbId(clazz, id));
        return coll.remove(query);
    }
    
    /**
     * Batch remove by id.
     * @param idList
     * @return 
     */
    public WriteResult remove(List<String> idList){
        int len = idList.size();
        Object[] arr = new Object[len];
        for(int i=0; i<len; i++){
            arr[i] = IdUtil.toDbId(clazz, idList.get(i));
        }
        DBObject in = new BasicDBObject(Operator.IN, arr);
        return removeMulti(new BasicDBObject(Operator.ID, in));
    }
    
    /**
     * Remove by condition.
     * @param key the condition field
     * @param value the condition value
     * @return 
     */
    public WriteResult remove(String key, Object value){
        value = checkSpecialValue(key, value);
        return removeMulti(new BasicDBObject(key, value));
    }
    
    /**
     * Remove by query condition.
     * @param query 
     * @return 
     */
    public WriteResult remove(BuguQuery query){
        return removeMulti(query.getCondition());
    }
    
    private WriteResult removeMulti(DBObject condition){
        if(!listenerList.isEmpty()){
            DBCursor cursor = coll.find(condition);
            List<T> list = MapperUtil.toList(clazz, cursor);
            for(T t : list){
                notifyDeleted((BuguEntity)t);
            }
        }
        return coll.remove(condition);
    }
    
    private Object checkSpecialValue(String key, Object value){
        Object result = value;
        if(value instanceof BuguEntity){
            BuguEntity be = (BuguEntity)value;
            result = ReferenceUtil.toDbReference(clazz, key, be.getClass(), be.getId());
        }else if(!(value instanceof DBObject) &&
                (FieldsCache.getInstance().isEmbedField(clazz, key) || FieldsCache.getInstance().isEmbedListField(clazz, key))){
            result = MapperUtil.toDBObject(value);
        }
        return result;
    }
    
    /**
     * Check if any entity with id already exists
     * @param id the id value to check
     * @return 
     */
    public boolean exists(String id){
        DBObject query = new BasicDBObject();
        query.put(Operator.ID, IdUtil.toDbId(clazz, id));
        return coll.findOne(query) != null;
    }
    
    /**
     * Check if any entity match the condition.
     * @param key the condition field
     * @param value the condition value
     * @return 
     */
    public boolean exists(String key, Object value){
        value = checkSpecialValue(key, value);
        DBObject query = new BasicDBObject(key, value);
        return coll.findOne(query) != null;
    }
    
    /**
     * Find a single document by natural order
     * @return 
     */
    public T findOne(){
        DBObject result = coll.findOne();
        return MapperUtil.fromDBObject(clazz, result);
    }
    
    /**
     * Find a single document by id
     * @param id
     * @return 
     */
    public T findOne(String id){
        DBObject query = new BasicDBObject();
        query.put(Operator.ID, IdUtil.toDbId(clazz, id));
        DBObject result = coll.findOne(query);
        return MapperUtil.fromDBObject(clazz, result);
    }
    
    /**
     * Find a single document by key-value
     * @param key
     * @param value
     * @return 
     */
    public T findOne(String key, Object value){
        value = checkSpecialValue(key, value);
        DBObject query = new BasicDBObject(key, value);
        DBObject dbo = coll.findOne(query);
        return MapperUtil.fromDBObject(clazz, dbo);
    }

    /**
     * Find all document by natural order
     * @return 
     */
    public List<T> findAll(){
        DBCursor cursor = coll.find(new BasicDBObject(), keys);
        return MapperUtil.toList(clazz, cursor);
    }
    
    /**
     * Find all document by order
     * @param orderBy
     * @return 
     */
    public List<T> findAll(String orderBy){
        DBObject dbo = SortUtil.getSort(orderBy);
        DBCursor cursor = coll.find(new BasicDBObject(), keys).sort(dbo);
        return MapperUtil.toList(clazz, cursor);
    }

    /**
     * Find all document, and return one page
     * @param pageNum
     * @param pageSize
     * @return 
     */
    public List<T> findAll(int pageNum, int pageSize){
        DBCursor cursor = coll.find(new BasicDBObject(), keys).skip((pageNum-1)*pageSize).limit(pageSize);
        return MapperUtil.toList(clazz, cursor);
    }
    
    /**
     * Find all document, and return one page
     * @param orderBy
     * @param pageNum
     * @param pageSize
     * @return 
     */
    public List<T> findAll(String orderBy, int pageNum, int pageSize){
        DBObject dbo = SortUtil.getSort(orderBy);
        DBCursor cursor = coll.find(new BasicDBObject(), keys).sort(dbo).skip((pageNum-1)*pageSize).limit(pageSize);
        return MapperUtil.toList(clazz, cursor);
    }
    
    /**
     * Atomically modify and return a single document. By default, the returned document does not include the modifications made on the update.
     * @param id
     * @param updater the modifications to apply
     * @return 
     */
    public T findAndModify(String id, BuguUpdater updater){
        return findAndModify(id, updater, false);
    }
    
    /**
     * Atomically modify and return a single document.
     * @param id
     * @param updater the modifications to apply
     * @param returnNew when true, returns the modified document rather than the original
     * @return 
     */
    public T findAndModify(String id, BuguUpdater updater, boolean returnNew){
        DBObject query = new BasicDBObject();
        query.put(Operator.ID, IdUtil.toDbId(clazz, id));
        DBObject result = coll.findAndModify(query, null, null, false, updater.getModifier(), returnNew, false);
        T t = MapperUtil.fromDBObject(clazz, result);
        if(!listenerList.isEmpty()){
            if(returnNew){
                notifyUpdated((BuguEntity)t);
            }else{
                BuguEntity entity = (BuguEntity)findOne(id);
                notifyUpdated(entity);
            }
        }
        return t;
    }
    
    /**
     * Atomically modify and return a single document. By default, the returned document does not include the modifications made on the update.
     * @param key
     * @param value
     * @param updater the modifications to apply
     * @return 
     */
    public T findAndModify(String key, Object value, BuguUpdater updater){
        return findAndModify(key, value, updater, false);
    }
    
    /**
     * Atomically modify and return a single document.
     * @param key
     * @param value
     * @param updater the modifications to apply
     * @param returnNew when true, returns the modified document rather than the original
     * @return 
     */
    public T findAndModify(String key, Object value, BuguUpdater updater, boolean returnNew){
        value = checkSpecialValue(key, value);
        DBObject query = new BasicDBObject(key, value);
        DBObject result = coll.findAndModify(query, null, null, false, updater.getModifier(), returnNew, false);
        T t = MapperUtil.fromDBObject(clazz, result);
        if(!listenerList.isEmpty()){
            if(returnNew){
                notifyUpdated((BuguEntity)t);
            }else{
                BuguEntity entity = (BuguEntity)findOne(key, value);
                notifyUpdated(entity);
            }
        }
        return t;
    }
    
    /**
     * Atomically modify and return a single document. By default, the returned document does not include the modifications made on the update.
     * @param query
     * @param updater the modifications to apply
     * @return 
     */
    public T findAndModify(BuguQuery query, BuguUpdater updater){
        return findAndModify(query, updater, false);
    }
    
    /**
     * Atomically modify and return a single document.
     * @param query
     * @param updater the modifications to apply
     * @param returnNew when true, returns the modified document rather than the original
     * @return 
     */
    public T findAndModify(BuguQuery query, BuguUpdater updater, boolean returnNew){
        DBObject result = coll.findAndModify(query.getCondition(), null, query.getSort(), false, updater.getModifier(), returnNew, false);
        T t = MapperUtil.fromDBObject(clazz, result);
        if(!listenerList.isEmpty()){
            if(returnNew){
                notifyUpdated((BuguEntity)t);
            }else{
                BuguEntity entity = (BuguEntity)query.result();
                notifyUpdated(entity);
            }
        }
        return t;
    }
    
    /**
     * Atomically remove and return a single document. The returned document is the original document before removal.
     * @param id
     * @return 
     */
    public T findAndRemove(String id){
        DBObject query = new BasicDBObject();
        query.put(Operator.ID, IdUtil.toDbId(clazz, id));
        return findAndRemove(query);
    }
    
    /**
     * Atomically remove and return a single document. The returned document is the original document before removal.
     * @param key
     * @param value
     * @return 
     */
    public T findAndRemove(String key, Object value){
        value = checkSpecialValue(key, value);
        DBObject query = new BasicDBObject(key, value);
        return findAndRemove(query);
    }
    
    /**
     * Atomically remove and return a single document. The returned document is the original document before removal.
     * @param query
     * @return 
     */
    public T findAndRemove(BuguQuery query){
        return findAndRemove(query.getCondition());
    }
    
    private T findAndRemove(DBObject dbo){
        DBObject result = coll.findAndModify(dbo, null, null, true, null, false, false);
        T t = MapperUtil.fromDBObject(clazz, result);
        if(!listenerList.isEmpty()){
            notifyDeleted((BuguEntity)t);
        }
        return t;
    }
    
    /**
     * Find the distinct values for a specified field across a collection and returns the results in an array.
     * @param key
     * @return 
     */
    public List distinct(String key){
        return coll.distinct(key);
    }

    /**
     * Count all entity.
     * @return 
     */
    public long count(){
        return coll.count();
    }
    
    /**
     * Count by condition.
     * @param key the condition field
     * @param value the condition value
     * @return 
     */
    public long count(String key, Object value){
        value = checkSpecialValue(key, value);
        return coll.count(new BasicDBObject(key, value));
    }
    
    /**
     * Get the DBCollection object, supplied by the mongodb java driver.
     * @return 
     */
    public DBCollection getCollection(){
        return coll;
    }

    public Class<T> getEntityClass() {
        return clazz;
    }

    public DBObject getKeyFields() {
        return keys;
    }

    public List<EntityListener> getListenerList() {
        return listenerList;
    }
    
    /**
     * Create a query.
     * @return a new BuguQuery object
     */
    public BuguQuery<T> query(){
        return new BuguQuery<T>(this);
    }
    
    /**
     * Create a updater.
     * @return a new BuguUpdater object
     */
    public BuguUpdater<T> update(){
        return new BuguUpdater(this);
    }
    
}
