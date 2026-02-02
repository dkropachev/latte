//! Type conversion between Rune values and DynamoDB AttributeValue.

use super::error::DynamoError;
use aws_sdk_dynamodb::types::AttributeValue;
use rune::alloc::String as RuneString;
use rune::runtime::{Object, Shared, Vec as RuneVec};
use rune::{Any, Value};

/// Wrapper for AttributeValue that can be exposed to Rune.
#[derive(Debug, Clone, Any)]
#[rune(item = ::dynamodb)]
pub struct DynamoValue {
    inner: AttributeValue,
}

impl DynamoValue {
    pub fn new(inner: AttributeValue) -> Self {
        Self { inner }
    }

    pub fn into_inner(self) -> AttributeValue {
        self.inner
    }

    pub fn as_inner(&self) -> &AttributeValue {
        &self.inner
    }
}

/// Convert a Rune Value to a DynamoDB AttributeValue.
pub fn rune_to_attribute_value(value: &Value) -> Result<AttributeValue, DynamoError> {
    match value {
        Value::Bool(b) => Ok(AttributeValue::Bool(*b)),

        Value::Integer(i) => Ok(AttributeValue::N(i.to_string())),

        Value::Float(f) => Ok(AttributeValue::N(f.to_string())),

        Value::String(s) => {
            let s = s.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to borrow string: {}", e))
            })?;
            Ok(AttributeValue::S(s.to_string()))
        }

        Value::Bytes(b) => {
            let bytes = b.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to borrow bytes: {}", e))
            })?;
            Ok(AttributeValue::B(aws_sdk_dynamodb::primitives::Blob::new(
                bytes.as_slice().to_vec(),
            )))
        }

        Value::Vec(vec) => {
            let vec = vec.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to borrow vec: {}", e))
            })?;

            // Try to determine if this is a set (SS, NS, BS) or a list (L)
            // For simplicity, we'll treat all Vecs as Lists unless explicitly typed
            let mut items = Vec::with_capacity(vec.len());
            for item in vec.iter() {
                items.push(rune_to_attribute_value(item)?);
            }
            Ok(AttributeValue::L(items))
        }

        Value::Object(obj) => {
            let obj = obj.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to borrow object: {}", e))
            })?;

            let mut map = std::collections::HashMap::with_capacity(obj.len());
            for (key, val) in obj.iter() {
                map.insert(key.to_string(), rune_to_attribute_value(val)?);
            }
            Ok(AttributeValue::M(map))
        }

        Value::Option(opt) => {
            let opt = opt.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to borrow option: {}", e))
            })?;
            match opt.as_ref() {
                Some(val) => rune_to_attribute_value(val),
                None => Ok(AttributeValue::Null(true)),
            }
        }

        // Handle DynamoValue type (already wrapped)
        Value::Any(any) => {
            let any_ref = any.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to borrow any: {}", e))
            })?;
            if let Some(dv) = any_ref.downcast_borrow_ref::<DynamoValue>() {
                Ok(dv.as_inner().clone())
            } else {
                Err(DynamoError::invalid_parameter(
                    "Unsupported type for conversion to AttributeValue",
                ))
            }
        }

        _ => Err(DynamoError::invalid_parameter(
            "Unsupported type for conversion to AttributeValue",
        )),
    }
}

/// Convert a DynamoDB AttributeValue to a Rune Value.
pub fn attribute_value_to_rune(attr: &AttributeValue) -> Result<Value, DynamoError> {
    match attr {
        AttributeValue::S(s) => {
            let rune_str = RuneString::try_from(s.clone()).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create RuneString: {}", e))
            })?;
            Ok(Value::String(Shared::new(rune_str).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        AttributeValue::N(n) => {
            // Try parsing as i64 first, then f64
            if let Ok(i) = n.parse::<i64>() {
                Ok(Value::Integer(i))
            } else if let Ok(f) = n.parse::<f64>() {
                Ok(Value::Float(f))
            } else {
                Err(DynamoError::invalid_parameter(format!(
                    "Failed to parse number: {}",
                    n
                )))
            }
        }

        AttributeValue::B(b) => {
            let bytes = rune::runtime::Bytes::from_vec(
                rune::alloc::Vec::try_from(b.as_ref().to_vec()).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Failed to create bytes: {}", e))
                })?,
            );
            Ok(Value::Bytes(Shared::new(bytes).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        AttributeValue::Bool(b) => Ok(Value::Bool(*b)),

        AttributeValue::Null(_) => Ok(Value::Option(Shared::new(None).map_err(|e| {
            DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
        })?)),

        AttributeValue::L(list) => {
            let mut rune_vec = RuneVec::with_capacity(list.len()).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to allocate RuneVec: {}", e))
            })?;
            for item in list {
                rune_vec.push(attribute_value_to_rune(item)?).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Failed to push to vec: {}", e))
                })?;
            }
            Ok(Value::Vec(Shared::new(rune_vec).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        AttributeValue::M(map) => {
            let mut rune_obj = Object::new();
            for (key, val) in map {
                let rune_key = RuneString::try_from(key.clone()).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Failed to create key: {}", e))
                })?;
                rune_obj
                    .insert(rune_key, attribute_value_to_rune(val)?)
                    .map_err(|e| {
                        DynamoError::invalid_parameter(format!(
                            "Failed to insert into object: {}",
                            e
                        ))
                    })?;
            }
            Ok(Value::Object(Shared::new(rune_obj).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        AttributeValue::Ss(ss) => {
            let mut rune_vec = RuneVec::with_capacity(ss.len()).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to allocate RuneVec: {}", e))
            })?;
            for s in ss {
                let rune_str = RuneString::try_from(s.clone()).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Failed to create string: {}", e))
                })?;
                rune_vec
                    .push(Value::String(Shared::new(rune_str).map_err(|e| {
                        DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
                    })?))
                    .map_err(|e| {
                        DynamoError::invalid_parameter(format!("Failed to push to vec: {}", e))
                    })?;
            }
            Ok(Value::Vec(Shared::new(rune_vec).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        AttributeValue::Ns(ns) => {
            let mut rune_vec = RuneVec::with_capacity(ns.len()).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to allocate RuneVec: {}", e))
            })?;
            for n in ns {
                let val = if let Ok(i) = n.parse::<i64>() {
                    Value::Integer(i)
                } else if let Ok(f) = n.parse::<f64>() {
                    Value::Float(f)
                } else {
                    return Err(DynamoError::invalid_parameter(format!(
                        "Failed to parse number in set: {}",
                        n
                    )));
                };
                rune_vec.push(val).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Failed to push to vec: {}", e))
                })?;
            }
            Ok(Value::Vec(Shared::new(rune_vec).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        AttributeValue::Bs(bs) => {
            let mut rune_vec = RuneVec::with_capacity(bs.len()).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to allocate RuneVec: {}", e))
            })?;
            for b in bs {
                let bytes = rune::runtime::Bytes::from_vec(
                    rune::alloc::Vec::try_from(b.as_ref().to_vec()).map_err(|e| {
                        DynamoError::invalid_parameter(format!("Failed to create bytes: {}", e))
                    })?,
                );
                rune_vec
                    .push(Value::Bytes(Shared::new(bytes).map_err(|e| {
                        DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
                    })?))
                    .map_err(|e| {
                        DynamoError::invalid_parameter(format!("Failed to push to vec: {}", e))
                    })?;
            }
            Ok(Value::Vec(Shared::new(rune_vec).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
            })?))
        }

        _ => Err(DynamoError::invalid_parameter(
            "Unknown AttributeValue variant",
        )),
    }
}

// Helper functions for explicit type construction (exposed to Rune via latte:: module)

/// Create a String AttributeValue.
pub fn make_s(s: &str) -> DynamoValue {
    DynamoValue::new(AttributeValue::S(s.to_string()))
}

/// Create a Number AttributeValue from i64.
pub fn make_n_i64(n: i64) -> DynamoValue {
    DynamoValue::new(AttributeValue::N(n.to_string()))
}

/// Create a Number AttributeValue from f64.
pub fn make_n_f64(n: f64) -> DynamoValue {
    DynamoValue::new(AttributeValue::N(n.to_string()))
}

/// Create a Binary AttributeValue.
pub fn make_b(bytes: &[u8]) -> DynamoValue {
    DynamoValue::new(AttributeValue::B(aws_sdk_dynamodb::primitives::Blob::new(
        bytes.to_vec(),
    )))
}

/// Create a Boolean AttributeValue.
pub fn make_bool(b: bool) -> DynamoValue {
    DynamoValue::new(AttributeValue::Bool(b))
}

/// Create a Null AttributeValue.
pub fn make_null() -> DynamoValue {
    DynamoValue::new(AttributeValue::Null(true))
}

/// Create a String Set AttributeValue.
pub fn make_ss(strings: Vec<String>) -> DynamoValue {
    DynamoValue::new(AttributeValue::Ss(strings))
}

/// Create a Number Set AttributeValue.
pub fn make_ns(numbers: Vec<String>) -> DynamoValue {
    DynamoValue::new(AttributeValue::Ns(numbers))
}

/// Create a Binary Set AttributeValue.
pub fn make_bs(blobs: Vec<Vec<u8>>) -> DynamoValue {
    let blobs: Vec<_> = blobs
        .into_iter()
        .map(aws_sdk_dynamodb::primitives::Blob::new)
        .collect();
    DynamoValue::new(AttributeValue::Bs(blobs))
}

/// Create a List AttributeValue.
pub fn make_list(items: Vec<AttributeValue>) -> DynamoValue {
    DynamoValue::new(AttributeValue::L(items))
}

/// Create a Map AttributeValue.
pub fn make_map(items: std::collections::HashMap<String, AttributeValue>) -> DynamoValue {
    DynamoValue::new(AttributeValue::M(items))
}

/// Convert a Rune Object to a HashMap of AttributeValues (for item construction).
#[allow(dead_code)]
pub fn rune_object_to_item(
    obj: &Object,
) -> Result<std::collections::HashMap<String, AttributeValue>, DynamoError> {
    let mut item = std::collections::HashMap::new();
    for (key, val) in obj.iter() {
        item.insert(key.to_string(), rune_to_attribute_value(val)?);
    }
    Ok(item)
}

/// Convert a HashMap of AttributeValues to a Rune Object.
#[allow(dead_code)]
pub fn item_to_rune_object(
    item: &std::collections::HashMap<String, AttributeValue>,
) -> Result<Value, DynamoError> {
    let mut obj = Object::new();
    for (key, val) in item {
        let rune_key = RuneString::try_from(key.clone())
            .map_err(|e| DynamoError::invalid_parameter(format!("Failed to create key: {}", e)))?;
        obj.insert(rune_key, attribute_value_to_rune(val)?)
            .map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to insert into object: {}", e))
            })?;
    }
    Ok(Value::Object(Shared::new(obj).map_err(|e| {
        DynamoError::invalid_parameter(format!("Failed to create Shared: {}", e))
    })?))
}

#[cfg(test)]
mod tests {
    use super::*;

    // ==================== DynamoDB -> Rune Conversion Tests ====================

    #[test]
    fn test_attribute_value_string_to_rune() {
        let attr = AttributeValue::S("hello".to_string());
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::String(s) = result {
            let s_ref = s.borrow_ref().unwrap();
            assert_eq!(s_ref.as_str(), "hello");
        } else {
            panic!("Expected Value::String");
        }
    }

    #[test]
    fn test_attribute_value_number_int_to_rune() {
        let attr = AttributeValue::N("42".to_string());
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Integer(i) = result {
            assert_eq!(i, 42);
        } else {
            panic!("Expected Value::Integer, got {:?}", result);
        }
    }

    #[test]
    fn test_attribute_value_number_float_to_rune() {
        let attr = AttributeValue::N("3.125".to_string());
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Float(f) = result {
            assert!((f - 3.125).abs() < 0.001);
        } else {
            panic!("Expected Value::Float, got {:?}", result);
        }
    }

    #[test]
    fn test_attribute_value_bool_to_rune() {
        let attr_true = AttributeValue::Bool(true);
        let attr_false = AttributeValue::Bool(false);

        if let Value::Bool(b) = attribute_value_to_rune(&attr_true).unwrap() {
            assert!(b);
        } else {
            panic!("Expected Value::Bool(true)");
        }

        if let Value::Bool(b) = attribute_value_to_rune(&attr_false).unwrap() {
            assert!(!b);
        } else {
            panic!("Expected Value::Bool(false)");
        }
    }

    #[test]
    fn test_attribute_value_null_to_rune() {
        let attr = AttributeValue::Null(true);
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Option(opt) = result {
            let opt_ref = opt.borrow_ref().unwrap();
            assert!(opt_ref.is_none());
        } else {
            panic!("Expected Value::Option(None)");
        }
    }

    #[test]
    fn test_attribute_value_binary_to_rune() {
        let data = vec![1u8, 2, 3, 4, 5];
        let attr = AttributeValue::B(aws_sdk_dynamodb::primitives::Blob::new(data.clone()));
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Bytes(b) = result {
            let b_ref = b.borrow_ref().unwrap();
            assert_eq!(b_ref.as_slice(), &data[..]);
        } else {
            panic!("Expected Value::Bytes");
        }
    }

    #[test]
    fn test_attribute_value_list_to_rune() {
        let attr = AttributeValue::L(vec![
            AttributeValue::S("a".to_string()),
            AttributeValue::N("1".to_string()),
        ]);
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Vec(vec) = result {
            let vec_ref = vec.borrow_ref().unwrap();
            assert_eq!(vec_ref.len(), 2);
        } else {
            panic!("Expected Value::Vec");
        }
    }

    #[test]
    fn test_attribute_value_string_set_to_rune() {
        let attr = AttributeValue::Ss(vec!["a".to_string(), "b".to_string(), "c".to_string()]);
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Vec(vec) = result {
            let vec_ref = vec.borrow_ref().unwrap();
            assert_eq!(vec_ref.len(), 3);
        } else {
            panic!("Expected Value::Vec");
        }
    }

    #[test]
    fn test_attribute_value_number_set_to_rune() {
        let attr = AttributeValue::Ns(vec!["1".to_string(), "2".to_string(), "3".to_string()]);
        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Vec(vec) = result {
            let vec_ref = vec.borrow_ref().unwrap();
            assert_eq!(vec_ref.len(), 3);
        } else {
            panic!("Expected Value::Vec");
        }
    }

    #[test]
    fn test_attribute_value_map_to_rune() {
        let mut map = std::collections::HashMap::new();
        map.insert("key1".to_string(), AttributeValue::S("value1".to_string()));
        map.insert("key2".to_string(), AttributeValue::N("42".to_string()));
        let attr = AttributeValue::M(map);

        let result = attribute_value_to_rune(&attr).unwrap();
        if let Value::Object(obj) = result {
            let obj_ref = obj.borrow_ref().unwrap();
            assert_eq!(obj_ref.len(), 2);
        } else {
            panic!("Expected Value::Object");
        }
    }

    // ==================== Helper Function Tests ====================

    #[test]
    fn test_make_s() {
        let dv = make_s("test");
        if let AttributeValue::S(s) = dv.into_inner() {
            assert_eq!(s, "test");
        } else {
            panic!("Expected AttributeValue::S");
        }
    }

    #[test]
    fn test_make_n_i64() {
        let dv = make_n_i64(42);
        if let AttributeValue::N(n) = dv.into_inner() {
            assert_eq!(n, "42");
        } else {
            panic!("Expected AttributeValue::N");
        }
    }

    #[test]
    fn test_make_n_f64() {
        let dv = make_n_f64(3.125);
        if let AttributeValue::N(n) = dv.into_inner() {
            assert_eq!(n, "3.125");
        } else {
            panic!("Expected AttributeValue::N");
        }
    }

    #[test]
    fn test_make_bool() {
        let dv_true = make_bool(true);
        let dv_false = make_bool(false);

        if let AttributeValue::Bool(b) = dv_true.into_inner() {
            assert!(b);
        } else {
            panic!("Expected AttributeValue::Bool(true)");
        }

        if let AttributeValue::Bool(b) = dv_false.into_inner() {
            assert!(!b);
        } else {
            panic!("Expected AttributeValue::Bool(false)");
        }
    }

    #[test]
    fn test_make_null() {
        let dv = make_null();
        if let AttributeValue::Null(n) = dv.into_inner() {
            assert!(n);
        } else {
            panic!("Expected AttributeValue::Null");
        }
    }

    #[test]
    fn test_make_b() {
        let data = vec![1u8, 2, 3];
        let dv = make_b(&data);
        if let AttributeValue::B(b) = dv.into_inner() {
            assert_eq!(b.as_ref(), &data[..]);
        } else {
            panic!("Expected AttributeValue::B");
        }
    }

    #[test]
    fn test_make_ss() {
        let strings = vec!["a".to_string(), "b".to_string()];
        let dv = make_ss(strings.clone());
        if let AttributeValue::Ss(ss) = dv.into_inner() {
            assert_eq!(ss, strings);
        } else {
            panic!("Expected AttributeValue::Ss");
        }
    }

    #[test]
    fn test_make_ns() {
        let numbers = vec!["1".to_string(), "2".to_string()];
        let dv = make_ns(numbers.clone());
        if let AttributeValue::Ns(ns) = dv.into_inner() {
            assert_eq!(ns, numbers);
        } else {
            panic!("Expected AttributeValue::Ns");
        }
    }

    #[test]
    fn test_make_list() {
        let items = vec![
            AttributeValue::S("a".to_string()),
            AttributeValue::N("1".to_string()),
        ];
        let dv = make_list(items.clone());
        if let AttributeValue::L(l) = dv.into_inner() {
            assert_eq!(l.len(), 2);
        } else {
            panic!("Expected AttributeValue::L");
        }
    }

    #[test]
    fn test_make_map() {
        let mut items = std::collections::HashMap::new();
        items.insert("key".to_string(), AttributeValue::S("value".to_string()));
        let dv = make_map(items);
        if let AttributeValue::M(m) = dv.into_inner() {
            assert_eq!(m.len(), 1);
            assert!(m.contains_key("key"));
        } else {
            panic!("Expected AttributeValue::M");
        }
    }
}
