use num_integer::Integer;

fn mul(a: u64, b: u64, m: u64) -> u64 { ((a as u128 * b as u128) % m as u128) as u64 }
fn pow(mut a: u64, mut n: u64, m: u64) -> u64 {
    let mut result = 1;
    while n > 0 { if n & 1 != 0 { result = mul(result, a, m); } a = mul(a, a, m); n >>= 1; }
    result
}
pub fn is_prime(n: u64) -> bool {
    if n < 2 { return false; }
    for p in [2,3,5,7,11,13,17,19,23,29,31,37] { if n % p == 0 { return n == p; } }
    let s = (n - 1).trailing_zeros(); let d = (n - 1) >> s;
    // This witness set is deterministic for the entire u64 range.
    'witness: for a in [2u64,325,9375,28178,450775,9780504,1795265022] {
        if a % n == 0 { continue; }
        let mut x = pow(a % n, d, n);
        if x == 1 || x == n - 1 { continue; }
        for _ in 1..s { x = mul(x, x, n); if x == n - 1 { continue 'witness; } }
        return false;
    }
    true
}
fn split(n: u64, budget: &mut usize) -> Result<u64, &'static str> {
    for p in [2,3,5,7,11,13,17,19,23,29,31,37] { if n % p == 0 { return Ok(p); } }
    for seed in 1..=32u64 {
        let c = seed * 2 + 1; let mut x = seed + 2; let mut y = x; let mut divisor = 1;
        let next = |v| ((mul(v, v, n) as u128 + c as u128) % n as u128) as u64;
        while divisor == 1 {
            if *budget == 0 { return Err("limit"); } *budget -= 1;
            x = next(x); y = next(next(y)); divisor = x.abs_diff(y).gcd(&n);
        }
        if divisor != n { return Ok(divisor); }
    }
    Err("limit")
}
fn factor(n: u64, out: &mut Vec<u64>, budget: &mut usize) -> Result<(), &'static str> {
    if n == 1 { return Ok(()); }
    if is_prime(n) { out.push(n); return Ok(()); }
    let divisor = split(n, budget)?;
    factor(divisor, out, budget)?; factor(n / divisor, out, budget)
}
pub fn output(source: &str, operation: &str, powers: bool) -> Result<String, &'static str> {
    if source.is_empty() || source.len() > 21 { return Err("number"); }
    if source.starts_with('-') && operation == "is_prime" && source[1..].parse::<u64>().is_ok() { return Ok("false".into()); }
    let number: u64 = source.parse().map_err(|_| "number")?;
    if operation == "is_prime" { return Ok(is_prime(number).to_string()); }
    if number == 0 { return Err("number"); }
    let mut factors = Vec::new(); factor(number, &mut factors, &mut 2_000_000)?; factors.sort();
    let mut grouped: Vec<(u64,usize)> = Vec::new();
    for value in &factors { match grouped.last_mut() { Some((last,count)) if last == value => *count += 1, _ => grouped.push((*value,1)) } }
    match operation {
        "prime_factors" => Ok(if powers { grouped.into_iter().map(|(p,n)| if n > 1 { format!("{p}^{n}") } else { p.to_string() }).collect::<Vec<_>>() } else { factors.into_iter().map(|p| p.to_string()).collect() }.join("\n")),
        "divisors" => {
            let mut result = vec![1u64];
            for (prime, count) in grouped {
                let original = result.clone(); let mut multiplier = 1u64;
                for _ in 0..count {
                    multiplier = multiplier.checked_mul(prime).ok_or("number")?;
                    if result.len() + original.len() > 256 { return Err("size"); }
                    for value in &original { result.push(value.checked_mul(multiplier).ok_or("number")?); }
                }
            }
            result.sort(); Ok(result.into_iter().map(|n| n.to_string()).collect::<Vec<_>>().join("\n"))
        }
        _ => Err("operation"),
    }
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn primality_and_factorization() {
        for p in [2,3,97,1_000_000_007,18_446_744_073_709_551_557] { assert!(is_prime(p),"{p}"); }
        for n in [0,1,4,341,561,3_215_031_751,u64::MAX] { assert!(!is_prime(n),"{n}"); }
        assert_eq!(output("6","prime_factors",false).unwrap(),"2\n3");
        assert_eq!(output("72","prime_factors",true).unwrap(),"2^3\n3^2");
        assert_eq!(output("8","prime_factors",false).unwrap(),"2\n2\n2");
        assert_eq!(output("6","divisors",false).unwrap(),"1\n2\n3\n6");
        assert_eq!(output("1000000016000000063","prime_factors",false).unwrap(),"1000000007\n1000000009");
    }
    #[test] fn edge_cases_and_limits() {
        assert_eq!(output("1","prime_factors",false).unwrap(),"");
        assert_eq!(output("1","divisors",false).unwrap(),"1");
        assert_eq!(output("-7","is_prime",false).unwrap(),"false");
        assert!(output("0","divisors",false).is_err()); assert!(output("2.5","prime_factors",false).is_err());
        assert!(output("18446744073709551616","is_prime",false).is_err());
        assert_eq!(output("73513440","divisors",false).unwrap_err(),"size");
    }
}
