use bigdecimal::{BigDecimal as D, RoundingMode};
use num_bigint::BigInt;
use num_integer::Integer;
use num_traits::{Zero, One, ToPrimitive, Signed};
use std::{str::FromStr, num::NonZeroU64};
type R = Result<D, &'static str>;

fn bound(n: D) -> R {
    let (_, scale) = n.as_bigint_and_exponent();
    if !(-1024..=1024).contains(&scale) || n.digits() > 1024 { return Err("number"); }
    Ok(n.with_precision_round(NonZeroU64::new(34).unwrap(), RoundingMode::HalfEven))
}
struct Parser<'a> { s: &'a [u8], i: usize, depth: u16, angle_unit: i32 }
impl<'a> Parser<'a> {
    fn skip(&mut self) { while self.i < self.s.len() && self.s[self.i].is_ascii_whitespace() { self.i += 1; } }
    fn take(&mut self, ch: u8) -> bool { self.skip(); if self.s.get(self.i) == Some(&ch) { self.i += 1; true } else { false } }
    fn sum(&mut self) -> R {
        let mut a = self.product()?;
        loop { if self.take(b'+') { a = bound(a + self.product()?)?; } else if self.take(b'-') { a = bound(a - self.product()?)?; } else { return Ok(a); } }
    }
    fn product(&mut self) -> R {
        let mut a = self.unary()?;
        loop {
            if self.take(b'*') { a = bound(a * self.unary()?)?; }
            else if self.take(b'/') { let b = self.unary()?; if b.is_zero() { return Err("zero"); } a = bound(a / b)?; }
            else if self.take(b'%') { let b = self.unary()?; if b.is_zero() { return Err("zero"); } a = bound(a % b)?; }
            else { return Ok(a); }
        }
    }
    fn unary(&mut self) -> R {
        self.depth += 1; if self.depth > 128 { return Err("depth"); }
        let result = if self.take(b'+') { self.unary() } else if self.take(b'-') { self.unary().map(|n| -n) } else { self.power() };
        self.depth -= 1; result
    }
    fn power(&mut self) -> R {
        let base = self.atom()?;
        if !self.take(b'^') { return Ok(base); }
        let number = self.unary()?; let exponent = number.to_i32().ok_or("exponent")?;
        if !(-100..=100).contains(&exponent) || number != D::from(exponent) { return Err("exponent"); }
        let mut out = D::one();
        for _ in 0..exponent.unsigned_abs() { out = bound(out * &base)?; }
        if exponent < 0 { if out.is_zero() { Err("zero") } else { bound(D::one() / out) } } else { Ok(out) }
    }
    fn atom(&mut self) -> R {
        self.skip();
        if self.take(b'(') { let n = self.sum()?; if !self.take(b')') { return Err("paren"); } return Ok(n); }
        let start = self.i;
        if self.s.get(self.i).map_or(false, |c| c.is_ascii_alphabetic() || *c == b'_') {
            while self.s.get(self.i).map_or(false, |c| c.is_ascii_alphanumeric() || *c == b'_') { self.i += 1; }
            let name = std::str::from_utf8(&self.s[start..self.i]).map_err(|_| "name")?;
            if !self.take(b'(') { return match name { "pi" => D::from_str("3.141592653589793238462643383279503").map_err(|_| "math"), "e" => D::from_str("2.718281828459045235360287471352662").map_err(|_| "math"), _ => Err("name") }; }
            let a = self.sum()?;
            let second = if self.take(b',') { Some(self.sum()?) } else { None };
            if !self.take(b')') { return Err("paren"); }
            return bound(match name {
                "abs" if second.is_none() => a.abs(),
                "round" | "ceil" | "floor" => {
                    let digits = match second { Some(n) => integer(&n)?.to_i64().ok_or("digits")?, None => 0 };
                    if !(-100..=100).contains(&digits) { return Err("digits"); }
                    a.with_scale_round(digits, match name { "ceil" => RoundingMode::Ceiling, "floor" => RoundingMode::Floor, _ => RoundingMode::HalfUp })
                }
                "root" => {
                    let degree = integer(&second.ok_or("arity")?)?.to_i32().ok_or("root")?;
                    if !(1..=1000).contains(&degree) || (a.is_negative() && degree % 2 == 0) { return Err("root"); }
                    if degree == 1 { a } else { let x = a.to_f64().ok_or("range")?; floating(x.abs().powf(1.0 / degree as f64) * if x < 0.0 { -1.0 } else { 1.0 })? }
                }
                "min" => a.min(second.ok_or("arity")?),
                "max" => a.max(second.ok_or("arity")?),
                "gcd" | "lcm" => {
                    let b = second.ok_or("arity")?;
                    let ai = integer(&a)?; let bi = integer(&b)?;
                    D::from(if name == "gcd" { ai.gcd(&bi) } else { ai.lcm(&bi) }.abs())
                }
                "mod" => { let m = second.ok_or("arity")?.abs(); if m.is_zero() { return Err("zero"); } let n = a % &m; if n < D::zero() { n + m } else { n } }
                "sin" | "cos" | "tan" | "asin" | "acos" | "atan" | "sqrt" | "cbrt" if second.is_none() => {
                    let x = a.to_f64().ok_or("range")?;
                    let scale = match self.angle_unit { 0 => std::f64::consts::PI / 180.0, 1 => 1.0, _ => std::f64::consts::PI / 200.0 };
                    let quarter = match self.angle_unit { 0 => 90.0, 1 => std::f64::consts::PI / 2.0, _ => 100.0 };
                    let turns = x / quarter;
                    let out = if matches!(name, "sin" | "cos" | "tan") && turns.is_finite() && turns.abs() <= 1e12 && turns.fract() == 0.0 {
                        let quadrant = turns.rem_euclid(4.0) as usize;
                        match name { "sin" => [0.0,1.0,0.0,-1.0][quadrant], "cos" => [1.0,0.0,-1.0,0.0][quadrant], _ => { if quadrant % 2 != 0 { return Err("domain"); } 0.0 } }
                    } else { match name { "sin" => (x * scale).sin(), "cos" => (x * scale).cos(), "tan" => (x * scale).tan(), "asin" => x.asin() / scale, "acos" => x.acos() / scale, "atan" => x.atan() / scale, "cbrt" => x.cbrt(), _ => x.sqrt() } };
                    floating(out)?
                }
                _ => return Err("function"),
            });
        }
        let mut digits = 0;
        while self.s.get(self.i).map_or(false, u8::is_ascii_digit) { self.i += 1; digits += 1; }
        if self.s.get(self.i) == Some(&b'.') { self.i += 1; while self.s.get(self.i).map_or(false, u8::is_ascii_digit) { self.i += 1; digits += 1; } }
        if digits == 0 || digits > 1024 { return Err("number"); }
        if matches!(self.s.get(self.i), Some(b'e') | Some(b'E')) {
            self.i += 1; if matches!(self.s.get(self.i), Some(b'+') | Some(b'-')) { self.i += 1; }
            let at = self.i; while self.s.get(self.i).map_or(false, u8::is_ascii_digit) { self.i += 1; }
            if self.i == at || self.i - at > 4 { return Err("exponent"); }
        }
        bound(D::from_str(std::str::from_utf8(&self.s[start..self.i]).map_err(|_| "number")?).map_err(|_| "number")?)
    }
}
// Transcendental f64 functions expose 15 reliable decimal digits, not binary tail noise.
fn floating(n: f64) -> R {
    if !n.is_finite() { return Err("domain"); }
    Ok(D::from_str(&n.to_string()).map_err(|_| "range")?.with_precision_round(NonZeroU64::new(15).unwrap(), RoundingMode::HalfEven))
}
fn integer(n: &D) -> Result<BigInt, &'static str> { let i = n.with_scale(0).as_bigint_and_exponent().0; if D::from(i.clone()) == *n { Ok(i) } else { Err("integer") } }
pub fn evaluate(source: &str) -> R { evaluate_with_unit(source, 0) }
pub fn evaluate_with_unit(source: &str, angle_unit: i32) -> R {
    if !(0..=2).contains(&angle_unit) { return Err("angle"); }
    if source.trim().is_empty() || source.len() > 65536 { return Err("size"); }
    let mut parser = Parser { s: source.as_bytes(), i: 0, depth: 0, angle_unit };
    let result = parser.sum()?; parser.skip(); if parser.i != source.len() { return Err("syntax"); } bound(result)
}

#[no_mangle]
pub extern "system" fn Java_com_germanium_shortcuts_NativeMath_evaluateNative(mut env: jni::JNIEnv, _class: jni::objects::JClass, source: jni::objects::JString, angle_unit: jni::sys::jint) -> jni::sys::jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let input: String = env.get_string(&source).map_err(|_| "jni")?.into();
        evaluate_with_unit(&input, angle_unit).map(|n| n.normalized().to_string())
    }));
    let output = match result { Ok(Ok(value)) => value, _ => "!math".into() };
    env.new_string(output).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn decimal_and_precedence() { for (s,out) in [("0.1+0.2","0.3"),("-2^2","-4"),("2^3^2","512"),("2^-2","0.25"),("round(-2.5)","-3"),("7%3","1")] { assert_eq!(evaluate(s).unwrap(),D::from_str(out).unwrap(),"{s}"); } }
    #[test] fn integer_functions() { for (s,n) in [("gcd(84,30)",6),("lcm(12,18)",36),("mod(-7,3)",2),("lcm(0,7)",0)] { assert_eq!(evaluate(s).unwrap(),D::from(n)); } }
    #[test] fn trig() { for (s,out) in [("sin(0)",0.0),("cos(0)",1.0),("tan(0)",0.0),("asin(0)",0.0),("acos(1)",0.0),("atan(0)",0.0)] { assert_eq!(evaluate(s).unwrap().to_f64().unwrap(),out); } }
    #[test] fn angle_units() {
        for (unit, angle, inverse) in [(0, 30.0, 30.0), (1, std::f64::consts::PI / 6.0, std::f64::consts::PI / 6.0), (2, 100.0 / 3.0, 100.0 / 3.0)] {
            assert!((evaluate_with_unit(&format!("sin({angle})"), unit).unwrap().to_f64().unwrap() - 0.5).abs() < 1e-12);
            assert!((evaluate_with_unit("asin(0.5)", unit).unwrap().to_f64().unwrap() - inverse).abs() < 1e-12);
            assert!((evaluate_with_unit("sin(asin(0.5))", unit).unwrap().to_f64().unwrap() - 0.5).abs() < 1e-12);
        }
    }
    #[test] fn clean_trig_roots_and_rounding() {
        for (s,n) in [("sin(30)","0.5"),("cos(90)","0"),("asin(0.5)","30"),("sqrt(9)","3"),("cbrt(-27)","-3"),("root(81,4)","3"),("root(-32,5)","-2"),("ceil(1.234,2)","1.24"),("floor(-1.234,2)","-1.24"),("round(1.235,2)","1.24"),("ceil(123,-1)","130")] {
            assert_eq!(evaluate(s).unwrap(), D::from_str(n).unwrap(), "{s}");
        }
        for s in ["root(-4,2)","root(2,0)","root(2,2.5)","ceil(1,101)","tan(90)"] { assert!(evaluate(s).is_err(), "{s}"); }
    }
    #[test] fn reject_invalid() { for s in ["1/0","2^101","gcd(1.5,2)","acos(2)","1e999999","unknown(2)","1+","1abc"] { assert!(evaluate(s).is_err(),"{s}"); } }
    #[test] fn depth_limit() { assert!(evaluate(&("(".repeat(140)+"1"+&")".repeat(140))).is_err()); }
}
