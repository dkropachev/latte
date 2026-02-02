//! Fluent builder operations for DynamoDB exposed to Rune.
//!
//! This module provides builder types that match the AWS SDK pattern.
//!
//! Note: Some helper functions (like `put_request`, `delete_request`, `gsi`, `lsi`) are marked
//! with `#[allow(dead_code)]` because they are part of the public API for advanced workloads
//! but may not be used in typical benchmarks. They are kept for API completeness.

use super::error::DynamoError;
use super::types::{rune_to_attribute_value, DynamoValue};
use aws_sdk_dynamodb::types::{
    AttributeDefinition, AttributeValue, DeleteRequest, GlobalSecondaryIndex, KeySchemaElement,
    KeyType, KeysAndAttributes, LocalSecondaryIndex, Projection, ProjectionType,
    ProvisionedThroughput, PutRequest, ScalarAttributeType, WriteRequest,
};
use rune::runtime::{Object, Ref, Vec as RuneVec, VmError, VmResult};
use rune::{vm_try, Any, Value};
use std::collections::HashMap;

// ==================== Helper Functions for Rune ====================

/// Create a KeySchemaElement.
pub fn key_schema(name: &str, key_type: &str) -> Result<KeySchemaElement, DynamoError> {
    let kt = match key_type.to_uppercase().as_str() {
        "HASH" => KeyType::Hash,
        "RANGE" => KeyType::Range,
        _ => {
            return Err(DynamoError::invalid_parameter(format!(
                "Invalid key type: {}. Must be HASH or RANGE",
                key_type
            )))
        }
    };

    KeySchemaElement::builder()
        .attribute_name(name)
        .key_type(kt)
        .build()
        .map_err(|e| {
            DynamoError::invalid_parameter(format!("Failed to build KeySchemaElement: {}", e))
        })
}

/// Create an AttributeDefinition.
pub fn attribute_def(name: &str, attr_type: &str) -> Result<AttributeDefinition, DynamoError> {
    let at = match attr_type.to_uppercase().as_str() {
        "S" => ScalarAttributeType::S,
        "N" => ScalarAttributeType::N,
        "B" => ScalarAttributeType::B,
        _ => {
            return Err(DynamoError::invalid_parameter(format!(
                "Invalid attribute type: {}. Must be S, N, or B",
                attr_type
            )))
        }
    };

    AttributeDefinition::builder()
        .attribute_name(name)
        .attribute_type(at)
        .build()
        .map_err(|e| {
            DynamoError::invalid_parameter(format!("Failed to build AttributeDefinition: {}", e))
        })
}

/// Create a ProvisionedThroughput.
pub fn throughput(
    read_capacity: i64,
    write_capacity: i64,
) -> Result<ProvisionedThroughput, DynamoError> {
    ProvisionedThroughput::builder()
        .read_capacity_units(read_capacity)
        .write_capacity_units(write_capacity)
        .build()
        .map_err(|e| {
            DynamoError::invalid_parameter(format!("Failed to build ProvisionedThroughput: {}", e))
        })
}

/// Create a PutRequest for batch operations.
#[allow(dead_code)]
pub fn put_request(item: HashMap<String, AttributeValue>) -> WriteRequest {
    WriteRequest::builder()
        .put_request(PutRequest::builder().set_item(Some(item)).build().unwrap())
        .build()
}

/// Create a DeleteRequest for batch operations.
#[allow(dead_code)]
pub fn delete_request(key: HashMap<String, AttributeValue>) -> WriteRequest {
    WriteRequest::builder()
        .delete_request(DeleteRequest::builder().set_key(Some(key)).build().unwrap())
        .build()
}

/// Create KeysAndAttributes for batch get operations.
#[allow(dead_code)]
pub fn keys_and_attributes(keys: Vec<HashMap<String, AttributeValue>>) -> KeysAndAttributes {
    KeysAndAttributes::builder()
        .set_keys(Some(keys))
        .build()
        .unwrap()
}

// ==================== GSI Builder ====================

/// Builder for Global Secondary Index.
#[derive(Clone)]
#[allow(dead_code)]
pub struct GsiBuilder {
    name: String,
    key_schema: Vec<KeySchemaElement>,
    projection_type: ProjectionType,
    non_key_attributes: Option<Vec<String>>,
    provisioned_throughput: Option<ProvisionedThroughput>,
}

#[allow(dead_code)]
impl GsiBuilder {
    pub fn new(name: &str) -> Self {
        Self {
            name: name.to_string(),
            key_schema: Vec::new(),
            projection_type: ProjectionType::All,
            non_key_attributes: None,
            provisioned_throughput: None,
        }
    }

    pub fn key_schema(mut self, element: KeySchemaElement) -> Self {
        self.key_schema.push(element);
        self
    }

    pub fn projection_type(mut self, proj_type: &str) -> Result<Self, DynamoError> {
        self.projection_type = match proj_type.to_uppercase().as_str() {
            "ALL" => ProjectionType::All,
            "KEYS_ONLY" => ProjectionType::KeysOnly,
            "INCLUDE" => ProjectionType::Include,
            _ => {
                return Err(DynamoError::invalid_parameter(format!(
                    "Invalid projection type: {}. Must be ALL, KEYS_ONLY, or INCLUDE",
                    proj_type
                )))
            }
        };
        Ok(self)
    }

    pub fn non_key_attributes(mut self, attrs: Vec<String>) -> Self {
        self.non_key_attributes = Some(attrs);
        self
    }

    pub fn provisioned_throughput(mut self, throughput: ProvisionedThroughput) -> Self {
        self.provisioned_throughput = Some(throughput);
        self
    }

    pub fn build(self) -> Result<GlobalSecondaryIndex, DynamoError> {
        let mut projection = Projection::builder().projection_type(self.projection_type);

        if let Some(attrs) = self.non_key_attributes {
            projection = projection.set_non_key_attributes(Some(attrs));
        }

        let mut builder = GlobalSecondaryIndex::builder()
            .index_name(&self.name)
            .set_key_schema(Some(self.key_schema))
            .projection(projection.build());

        if let Some(throughput) = self.provisioned_throughput {
            builder = builder.provisioned_throughput(throughput);
        }

        builder.build().map_err(|e| {
            DynamoError::invalid_parameter(format!("Failed to build GlobalSecondaryIndex: {}", e))
        })
    }
}

/// Create a GSI builder.
#[allow(dead_code)]
pub fn gsi(name: &str) -> GsiBuilder {
    GsiBuilder::new(name)
}

// ==================== LSI Builder ====================

/// Builder for Local Secondary Index.
#[derive(Clone)]
#[allow(dead_code)]
pub struct LsiBuilder {
    name: String,
    key_schema: Vec<KeySchemaElement>,
    projection_type: ProjectionType,
    non_key_attributes: Option<Vec<String>>,
}

#[allow(dead_code)]
impl LsiBuilder {
    pub fn new(name: &str) -> Self {
        Self {
            name: name.to_string(),
            key_schema: Vec::new(),
            projection_type: ProjectionType::All,
            non_key_attributes: None,
        }
    }

    pub fn key_schema(mut self, element: KeySchemaElement) -> Self {
        self.key_schema.push(element);
        self
    }

    pub fn projection_type(mut self, proj_type: &str) -> Result<Self, DynamoError> {
        self.projection_type = match proj_type.to_uppercase().as_str() {
            "ALL" => ProjectionType::All,
            "KEYS_ONLY" => ProjectionType::KeysOnly,
            "INCLUDE" => ProjectionType::Include,
            _ => {
                return Err(DynamoError::invalid_parameter(format!(
                    "Invalid projection type: {}. Must be ALL, KEYS_ONLY, or INCLUDE",
                    proj_type
                )))
            }
        };
        Ok(self)
    }

    pub fn non_key_attributes(mut self, attrs: Vec<String>) -> Self {
        self.non_key_attributes = Some(attrs);
        self
    }

    pub fn build(self) -> Result<LocalSecondaryIndex, DynamoError> {
        let mut projection = Projection::builder().projection_type(self.projection_type);

        if let Some(attrs) = self.non_key_attributes {
            projection = projection.set_non_key_attributes(Some(attrs));
        }

        LocalSecondaryIndex::builder()
            .index_name(&self.name)
            .set_key_schema(Some(self.key_schema))
            .projection(projection.build())
            .build()
            .map_err(|e| {
                DynamoError::invalid_parameter(format!(
                    "Failed to build LocalSecondaryIndex: {}",
                    e
                ))
            })
    }
}

/// Create an LSI builder.
#[allow(dead_code)]
pub fn lsi(name: &str) -> LsiBuilder {
    LsiBuilder::new(name)
}

// ==================== Rune-Exposed Functions ====================

/// Create a String AttributeValue.
#[rune::function]
pub fn dynamo_s(s: &str) -> DynamoValue {
    super::types::make_s(s)
}

/// Create a Number AttributeValue from i64.
#[rune::function]
pub fn dynamo_n(n: i64) -> DynamoValue {
    super::types::make_n_i64(n)
}

/// Create a Number AttributeValue from f64.
#[rune::function]
pub fn dynamo_n_f64(n: f64) -> DynamoValue {
    super::types::make_n_f64(n)
}

/// Create a Boolean AttributeValue.
#[rune::function]
pub fn dynamo_bool(b: bool) -> DynamoValue {
    super::types::make_bool(b)
}

/// Create a Null AttributeValue.
#[rune::function]
pub fn dynamo_null() -> DynamoValue {
    super::types::make_null()
}

/// Create a Binary AttributeValue from bytes.
#[rune::function(vm_result)]
pub fn dynamo_b(bytes: Ref<rune::runtime::Bytes>) -> VmResult<DynamoValue> {
    VmResult::Ok(super::types::make_b(bytes.as_slice()))
}

/// Create a Binary Set AttributeValue.
#[rune::function(vm_result)]
pub fn dynamo_bs(blobs: Ref<RuneVec>) -> VmResult<DynamoValue> {
    let mut result = Vec::with_capacity(blobs.len());
    for item in blobs.iter() {
        if let Value::Bytes(b) = item {
            let b_ref = vm_try!(b.borrow_ref().map_err(|e| VmError::panic(format!("{}", e))));
            result.push(b_ref.as_slice().to_vec());
        } else {
            vm_try!(Err::<(), _>(VmError::panic("BS requires bytes values")));
        }
    }
    VmResult::Ok(super::types::make_bs(result))
}

/// Create a String Set AttributeValue.
#[rune::function(vm_result)]
pub fn dynamo_ss(strings: Ref<RuneVec>) -> VmResult<DynamoValue> {
    let mut result = Vec::with_capacity(strings.len());
    for item in strings.iter() {
        if let Value::String(s) = item {
            let s_ref = vm_try!(s.borrow_ref().map_err(|e| VmError::panic(format!("{}", e))));
            result.push(s_ref.to_string());
        } else {
            vm_try!(Err::<(), _>(VmError::panic("SS requires string values")));
        }
    }
    VmResult::Ok(super::types::make_ss(result))
}

/// Create a Number Set AttributeValue.
#[rune::function(vm_result)]
pub fn dynamo_ns(numbers: Ref<RuneVec>) -> VmResult<DynamoValue> {
    let mut result = Vec::with_capacity(numbers.len());
    for item in numbers.iter() {
        match item {
            Value::Integer(i) => result.push(i.to_string()),
            Value::Float(f) => result.push(f.to_string()),
            _ => {
                vm_try!(Err::<(), _>(VmError::panic("NS requires numeric values")));
            }
        }
    }
    VmResult::Ok(super::types::make_ns(result))
}

/// Create a List AttributeValue.
#[rune::function(vm_result)]
pub fn dynamo_l(items: Ref<RuneVec>) -> VmResult<DynamoValue> {
    let mut result = Vec::with_capacity(items.len());
    for item in items.iter() {
        let attr = vm_try!(rune_to_attribute_value(item)
            .map_err(|e| VmError::panic(format!("Failed to convert value: {}", e))));
        result.push(attr);
    }
    VmResult::Ok(super::types::make_list(result))
}

/// Create a Map AttributeValue.
#[rune::function(vm_result)]
pub fn dynamo_m(obj: Ref<Object>) -> VmResult<DynamoValue> {
    let mut result = HashMap::with_capacity(obj.len());
    for (key, val) in obj.iter() {
        let attr = vm_try!(rune_to_attribute_value(val)
            .map_err(|e| VmError::panic(format!("Failed to convert value: {}", e))));
        result.insert(key.to_string(), attr);
    }
    VmResult::Ok(super::types::make_map(result))
}

/// Create a KeySchemaElement wrapper.
#[derive(Debug, Clone, Any)]
#[rune(item = ::dynamodb)]
pub struct KeySchemaWrapper {
    pub inner: KeySchemaElement,
}

/// Create a KeySchemaElement.
#[rune::function(vm_result)]
pub fn dynamo_key_schema(name: &str, key_type: &str) -> VmResult<KeySchemaWrapper> {
    let ks = vm_try!(key_schema(name, key_type).map_err(|e| VmError::panic(format!("{}", e))));
    VmResult::Ok(KeySchemaWrapper { inner: ks })
}

/// Wrapper for AttributeDefinition.
#[derive(Debug, Clone, Any)]
#[rune(item = ::dynamodb)]
pub struct AttributeDefWrapper {
    pub inner: AttributeDefinition,
}

/// Create an AttributeDefinition.
#[rune::function(vm_result)]
pub fn dynamo_attribute_def(name: &str, attr_type: &str) -> VmResult<AttributeDefWrapper> {
    let ad = vm_try!(attribute_def(name, attr_type).map_err(|e| VmError::panic(format!("{}", e))));
    VmResult::Ok(AttributeDefWrapper { inner: ad })
}

/// Wrapper for ProvisionedThroughput.
#[derive(Debug, Clone, Any)]
#[rune(item = ::dynamodb)]
pub struct ThroughputWrapper {
    pub inner: ProvisionedThroughput,
}

/// Create a ProvisionedThroughput.
#[rune::function(vm_result)]
pub fn dynamo_throughput(read: i64, write: i64) -> VmResult<ThroughputWrapper> {
    let pt = vm_try!(throughput(read, write).map_err(|e| VmError::panic(format!("{}", e))));
    VmResult::Ok(ThroughputWrapper { inner: pt })
}

// ==================== Transaction Helper Wrappers ====================

/// Wrapper for TransactWriteItem.
#[derive(Debug, Clone, Any)]
#[rune(item = ::dynamodb)]
pub struct TransactWriteItemWrapper {
    pub inner: aws_sdk_dynamodb::types::TransactWriteItem,
}

/// Wrapper for TransactGetItem.
#[derive(Debug, Clone, Any)]
#[rune(item = ::dynamodb)]
pub struct TransactGetItemWrapper {
    pub inner: aws_sdk_dynamodb::types::TransactGetItem,
}

/// Create a TransactWriteItem for Put operation (internal use).
pub fn transact_put_internal(
    table_name: &str,
    item: HashMap<String, AttributeValue>,
    condition_expression: Option<String>,
) -> Result<aws_sdk_dynamodb::types::TransactWriteItem, String> {
    let mut put = aws_sdk_dynamodb::types::Put::builder()
        .table_name(table_name)
        .set_item(Some(item));

    if let Some(cond) = condition_expression {
        put = put.condition_expression(cond);
    }

    let put = put
        .build()
        .map_err(|e| format!("Failed to build Put: {}", e))?;

    Ok(aws_sdk_dynamodb::types::TransactWriteItem::builder()
        .put(put)
        .build())
}

/// Create a TransactWriteItem for Delete operation (internal use).
pub fn transact_delete_internal(
    table_name: &str,
    key: HashMap<String, AttributeValue>,
    condition_expression: Option<String>,
) -> Result<aws_sdk_dynamodb::types::TransactWriteItem, String> {
    let mut delete = aws_sdk_dynamodb::types::Delete::builder()
        .table_name(table_name)
        .set_key(Some(key));

    if let Some(cond) = condition_expression {
        delete = delete.condition_expression(cond);
    }

    let delete = delete
        .build()
        .map_err(|e| format!("Failed to build Delete: {}", e))?;

    Ok(aws_sdk_dynamodb::types::TransactWriteItem::builder()
        .delete(delete)
        .build())
}

/// Create a TransactGetItem for Get operation (internal use).
pub fn transact_get_internal(
    table_name: &str,
    key: HashMap<String, AttributeValue>,
    projection_expression: Option<String>,
) -> Result<aws_sdk_dynamodb::types::TransactGetItem, String> {
    let mut get = aws_sdk_dynamodb::types::Get::builder()
        .table_name(table_name)
        .set_key(Some(key));

    if let Some(proj) = projection_expression {
        get = get.projection_expression(proj);
    }

    let get = get
        .build()
        .map_err(|e| format!("Failed to build Get: {}", e))?;

    Ok(aws_sdk_dynamodb::types::TransactGetItem::builder()
        .get(get)
        .build())
}

/// Create a TransactWriteItem for Put operation (Rune-exposed).
#[rune::function(vm_result)]
pub fn dynamo_transact_put(
    table_name: Ref<str>,
    item: Ref<Object>,
) -> VmResult<TransactWriteItemWrapper> {
    let mut item_map = HashMap::with_capacity(item.len());
    for (key, val) in item.iter() {
        let attr = vm_try!(rune_to_attribute_value(val)
            .map_err(|e| VmError::panic(format!("Failed to convert value: {}", e))));
        item_map.insert(key.to_string(), attr);
    }

    let twi = vm_try!(transact_put_internal(&table_name, item_map, None).map_err(VmError::panic));

    VmResult::Ok(TransactWriteItemWrapper { inner: twi })
}

/// Create a TransactWriteItem for Delete operation (Rune-exposed).
#[rune::function(vm_result)]
pub fn dynamo_transact_delete(
    table_name: Ref<str>,
    key: Ref<Object>,
) -> VmResult<TransactWriteItemWrapper> {
    let mut key_map = HashMap::with_capacity(key.len());
    for (k, val) in key.iter() {
        let attr = vm_try!(rune_to_attribute_value(val)
            .map_err(|e| VmError::panic(format!("Failed to convert key: {}", e))));
        key_map.insert(k.to_string(), attr);
    }

    let twi = vm_try!(transact_delete_internal(&table_name, key_map, None).map_err(VmError::panic));

    VmResult::Ok(TransactWriteItemWrapper { inner: twi })
}

/// Create a TransactGetItem for Get operation (Rune-exposed).
#[rune::function(vm_result)]
pub fn dynamo_transact_get(
    table_name: Ref<str>,
    key: Ref<Object>,
) -> VmResult<TransactGetItemWrapper> {
    let mut key_map = HashMap::with_capacity(key.len());
    for (k, val) in key.iter() {
        let attr = vm_try!(rune_to_attribute_value(val)
            .map_err(|e| VmError::panic(format!("Failed to convert key: {}", e))));
        key_map.insert(k.to_string(), attr);
    }

    let tgi = vm_try!(transact_get_internal(&table_name, key_map, None).map_err(VmError::panic));

    VmResult::Ok(TransactGetItemWrapper { inner: tgi })
}

/// Create a TransactWriteItem for Update operation (internal use).
pub fn transact_update_internal(
    table_name: &str,
    key: HashMap<String, AttributeValue>,
    update_expression: &str,
    condition_expression: Option<String>,
    expression_attribute_names: Option<HashMap<String, String>>,
    expression_attribute_values: Option<HashMap<String, AttributeValue>>,
) -> Result<aws_sdk_dynamodb::types::TransactWriteItem, String> {
    let mut update = aws_sdk_dynamodb::types::Update::builder()
        .table_name(table_name)
        .set_key(Some(key))
        .update_expression(update_expression);

    if let Some(cond) = condition_expression {
        update = update.condition_expression(cond);
    }

    if let Some(names) = expression_attribute_names {
        update = update.set_expression_attribute_names(Some(names));
    }

    if let Some(values) = expression_attribute_values {
        update = update.set_expression_attribute_values(Some(values));
    }

    let update = update
        .build()
        .map_err(|e| format!("Failed to build Update: {}", e))?;

    Ok(aws_sdk_dynamodb::types::TransactWriteItem::builder()
        .update(update)
        .build())
}

/// Create a TransactWriteItem for ConditionCheck operation (internal use).
pub fn transact_condition_check_internal(
    table_name: &str,
    key: HashMap<String, AttributeValue>,
    condition_expression: &str,
    expression_attribute_names: Option<HashMap<String, String>>,
    expression_attribute_values: Option<HashMap<String, AttributeValue>>,
) -> Result<aws_sdk_dynamodb::types::TransactWriteItem, String> {
    let mut condition_check = aws_sdk_dynamodb::types::ConditionCheck::builder()
        .table_name(table_name)
        .set_key(Some(key))
        .condition_expression(condition_expression);

    if let Some(names) = expression_attribute_names {
        condition_check = condition_check.set_expression_attribute_names(Some(names));
    }

    if let Some(values) = expression_attribute_values {
        condition_check = condition_check.set_expression_attribute_values(Some(values));
    }

    let condition_check = condition_check
        .build()
        .map_err(|e| format!("Failed to build ConditionCheck: {}", e))?;

    Ok(aws_sdk_dynamodb::types::TransactWriteItem::builder()
        .condition_check(condition_check)
        .build())
}

/// Create a TransactWriteItem for Update operation (Rune-exposed).
///
/// Parameters:
/// - table_name: The table to update
/// - key: Object containing the key attributes
/// - update_expression: The update expression (e.g., "SET #n = :val")
/// - expression_values: Object containing expression attribute values (optional)
#[rune::function(vm_result)]
pub fn dynamo_transact_update(
    table_name: Ref<str>,
    key: Ref<Object>,
    update_expression: Ref<str>,
    expression_values: Option<Ref<Object>>,
) -> VmResult<TransactWriteItemWrapper> {
    let mut key_map = HashMap::with_capacity(key.len());
    for (k, val) in key.iter() {
        let attr = vm_try!(rune_to_attribute_value(val)
            .map_err(|e| VmError::panic(format!("Failed to convert key: {}", e))));
        key_map.insert(k.to_string(), attr);
    }

    let expr_values = if let Some(values_obj) = expression_values {
        let mut values_map = HashMap::with_capacity(values_obj.len());
        for (k, val) in values_obj.iter() {
            let attr = vm_try!(rune_to_attribute_value(val)
                .map_err(|e| VmError::panic(format!("Failed to convert expression value: {}", e))));
            values_map.insert(k.to_string(), attr);
        }
        Some(values_map)
    } else {
        None
    };

    let twi = vm_try!(transact_update_internal(
        &table_name,
        key_map,
        &update_expression,
        None, // condition_expression
        None, // expression_attribute_names
        expr_values,
    )
    .map_err(VmError::panic));

    VmResult::Ok(TransactWriteItemWrapper { inner: twi })
}

/// Create a TransactWriteItem for ConditionCheck operation (Rune-exposed).
///
/// Parameters:
/// - table_name: The table to check
/// - key: Object containing the key attributes
/// - condition_expression: The condition expression (e.g., "attribute_exists(pk)")
/// - expression_values: Object containing expression attribute values (optional)
#[rune::function(vm_result)]
pub fn dynamo_transact_condition_check(
    table_name: Ref<str>,
    key: Ref<Object>,
    condition_expression: Ref<str>,
    expression_values: Option<Ref<Object>>,
) -> VmResult<TransactWriteItemWrapper> {
    let mut key_map = HashMap::with_capacity(key.len());
    for (k, val) in key.iter() {
        let attr = vm_try!(rune_to_attribute_value(val)
            .map_err(|e| VmError::panic(format!("Failed to convert key: {}", e))));
        key_map.insert(k.to_string(), attr);
    }

    let expr_values = if let Some(values_obj) = expression_values {
        let mut values_map = HashMap::with_capacity(values_obj.len());
        for (k, val) in values_obj.iter() {
            let attr = vm_try!(rune_to_attribute_value(val)
                .map_err(|e| VmError::panic(format!("Failed to convert expression value: {}", e))));
            values_map.insert(k.to_string(), attr);
        }
        Some(values_map)
    } else {
        None
    };

    let twi = vm_try!(transact_condition_check_internal(
        &table_name,
        key_map,
        &condition_expression,
        None, // expression_attribute_names
        expr_values,
    )
    .map_err(VmError::panic));

    VmResult::Ok(TransactWriteItemWrapper { inner: twi })
}
